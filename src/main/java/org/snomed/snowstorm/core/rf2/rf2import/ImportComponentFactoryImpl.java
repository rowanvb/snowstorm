package org.snomed.snowstorm.core.rf2.rf2import;

import com.google.common.collect.Sets;
import io.kaicode.elasticvc.api.BranchCriteria;
import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.api.VersionControlHelper;
import io.kaicode.elasticvc.domain.Commit;
import io.kaicode.elasticvc.domain.Entity;
import org.ihtsdo.otf.snomedboot.factory.ImpotentComponentFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.domain.*;
import org.snomed.snowstorm.core.data.services.BranchMetadataHelper;
import org.snomed.snowstorm.core.data.services.ConceptUpdateHelper;
import org.snomed.snowstorm.core.data.services.ReferenceSetMemberService;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.util.CloseableIterator;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.kaicode.elasticvc.api.ComponentService.LARGE_PAGE;
import static java.lang.Long.parseLong;
import static org.elasticsearch.index.query.QueryBuilders.*;

public class ImportComponentFactoryImpl extends ImpotentComponentFactory {

	private static Logger logger = LoggerFactory.getLogger(ImportComponentFactoryImpl.class);
	private static final int FLUSH_INTERVAL = 5000;
	private static final int MEMBER_ADDITIONAL_FIELD_OFFSET = 6;
	private static final Pattern EFFECTIVE_DATE_PATTERN = Pattern.compile("\\d{8}");

	private final BranchService branchService;
	private final BranchMetadataHelper branchMetadataHelper;
	private final VersionControlHelper versionControlHelper;
	private final String path;
	private Commit commit;
	private BranchCriteria branchCriteriaBeforeOpenCommit;

	private PersistBuffer<Concept> conceptPersistBuffer;
	private PersistBuffer<Description> descriptionPersistBuffer;
	private PersistBuffer<Relationship> relationshipPersistBuffer;
	private PersistBuffer<ReferenceSetMember> memberPersistBuffer;
	private List<PersistBuffer> persistBuffers;
	private List<PersistBuffer> coreComponentPersistBuffers;
	private MaxEffectiveTimeCollector maxEffectiveTimeCollector;

	// A small number of stated relationships also appear in the inferred file. These should not be persisted when importing a snapshot.
	Set<Long> statedRelationshipsToSkip = Sets.newHashSet(3187444026L, 3192499027L, 3574321020L);
	boolean coreComponentsFlushed;

	ImportComponentFactoryImpl(ConceptUpdateHelper conceptUpdateHelper, ReferenceSetMemberService memberService, BranchService branchService,
			BranchMetadataHelper branchMetadataHelper, String path, Integer patchReleaseVersion, boolean copyReleaseFields) {

		this.branchService = branchService;
		this.branchMetadataHelper = branchMetadataHelper;
		this.path = path;
		persistBuffers = new ArrayList<>();
		maxEffectiveTimeCollector = new MaxEffectiveTimeCollector();
		coreComponentPersistBuffers = new ArrayList<>();
		ElasticsearchOperations elasticsearchTemplate = conceptUpdateHelper.getElasticsearchTemplate();
		versionControlHelper = conceptUpdateHelper.getVersionControlHelper();

		conceptPersistBuffer = new PersistBuffer<Concept>() {
			@Override
			public void persistCollection(Collection<Concept> entities) {
				processEntities(entities, patchReleaseVersion, elasticsearchTemplate, Concept.class, copyReleaseFields);
				if (!entities.isEmpty()) {
					conceptUpdateHelper.doSaveBatchConcepts(entities, commit);
				}
			}
		};
		coreComponentPersistBuffers.add(conceptPersistBuffer);

		descriptionPersistBuffer = new PersistBuffer<Description>() {
			@Override
			public void persistCollection(Collection<Description> entities) {
				processEntities(entities, patchReleaseVersion, elasticsearchTemplate, Description.class, copyReleaseFields);
				if (!entities.isEmpty()) {
					conceptUpdateHelper.doSaveBatchDescriptions(entities, commit);
				}
			}
		};
		coreComponentPersistBuffers.add(descriptionPersistBuffer);

		relationshipPersistBuffer = new PersistBuffer<Relationship>() {
			@Override
			public void persistCollection(Collection<Relationship> entities) {
				processEntities(entities, patchReleaseVersion, elasticsearchTemplate, Relationship.class, copyReleaseFields);
				if (!entities.isEmpty()) {
					conceptUpdateHelper.doSaveBatchRelationships(entities, commit);
				}
			}
		};
		coreComponentPersistBuffers.add(relationshipPersistBuffer);

		memberPersistBuffer = new PersistBuffer<ReferenceSetMember>() {
			@Override
			public void persistCollection(Collection<ReferenceSetMember> entities) {
				if (!coreComponentsFlushed) { // Avoid having to sync to check this
					synchronized (this) {
						if (!coreComponentsFlushed) {
							coreComponentPersistBuffers.forEach(PersistBuffer::flush);
							coreComponentsFlushed = true;
						}
					}
				}
				processEntities(entities, patchReleaseVersion, elasticsearchTemplate, ReferenceSetMember.class, copyReleaseFields);
				if (!entities.isEmpty()) {
					memberService.doSaveBatchMembers(entities, commit);
				}
			}
		};
	}

	/*
		- Mark as changed for version control.
		- Remove if earlier or equal effectiveTime to existing.
		- Copy release fields from existing.
	 */
	private <T extends SnomedComponent> void processEntities(Collection<T> components, Integer patchReleaseVersion, ElasticsearchOperations elasticsearchTemplate,
			Class<T> componentClass, boolean copyReleaseFields) {

		Map<Integer, List<T>> effectiveDateMap = new HashMap<>();
		components.forEach(component -> {
			component.setChanged(true);
			Integer effectiveTimeI = component.getEffectiveTimeI();
			if (effectiveTimeI != null) {
				effectiveDateMap.computeIfAbsent(effectiveTimeI, i -> new ArrayList<>()).add(component);
				maxEffectiveTimeCollector.add(effectiveTimeI);
			}
		});
		for (Integer effectiveTime : new TreeSet<>(effectiveDateMap.keySet())) {
			// Find component states with an equal or greater effective time
			boolean replacementOfThisEffectiveTimeAllowed = patchReleaseVersion != null && patchReleaseVersion.equals(effectiveTime);
			List<T> componentsAtDate = effectiveDateMap.get(effectiveTime);
			String idField = componentsAtDate.get(0).getIdField();
			AtomicInteger alreadyExistingComponentCount = new AtomicInteger();
			try (CloseableIterator<T> componentsWithSameOrLaterEffectiveTime = elasticsearchTemplate.stream(new NativeSearchQueryBuilder()
					.withQuery(boolQuery()
							.must(branchCriteriaBeforeOpenCommit.getEntityBranchCriteria(componentClass))
							.must(termsQuery(idField, componentsAtDate.stream().map(T::getId).collect(Collectors.toList())))
							.must(replacementOfThisEffectiveTimeAllowed ?
									rangeQuery(SnomedComponent.Fields.EFFECTIVE_TIME).gt(effectiveTime)
									: rangeQuery(SnomedComponent.Fields.EFFECTIVE_TIME).gte(effectiveTime)))
					.withFields(idField)// Only fetch the id
					.withPageable(LARGE_PAGE)
					.build(), componentClass)) {
				componentsWithSameOrLaterEffectiveTime.forEachRemaining(component -> {
					components.remove(component);// Compared by id only
					alreadyExistingComponentCount.incrementAndGet();
				});
			}
			if (alreadyExistingComponentCount.get() > 0) {
				// Remove ineffective components
				logger.info("{} {} components in the RF2 import with effectiveTime {} will not be imported because components already exist " +
						"with the same identifier at the same or later effectiveTime.", alreadyExistingComponentCount.get(), componentClass.getSimpleName(), effectiveTime);
			}
		}
		if (copyReleaseFields) {
			Map<String, T> idToUnreleasedComponentMap = components.stream().filter(component -> component.getEffectiveTime() == null).collect(Collectors.toMap(T::getId, Function.identity()));
			if (!idToUnreleasedComponentMap.isEmpty()) {
				String idField = idToUnreleasedComponentMap.values().iterator().next().getIdField();
				try (CloseableIterator<T> stream = elasticsearchTemplate.stream(new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(branchCriteriaBeforeOpenCommit.getEntityBranchCriteria(componentClass))
								.must(termQuery(SnomedComponent.Fields.RELEASED, true))
								.filter(termsQuery(idField, idToUnreleasedComponentMap.keySet()))
						)
						.withPageable(LARGE_PAGE)
						.build(), componentClass)) {
					stream.forEachRemaining(component -> {
						T t = idToUnreleasedComponentMap.get(component.getId());
						// noinspection unchecked
						t.copyReleaseDetails(component);
						t.updateEffectiveTime();
					});
				}
			}
		}
	}

	@Override
	public void loadingComponentsStarting() {
		commit = branchService.openCommit(path, branchMetadataHelper.getBranchLockMetadata("Loading components from RF2 import."));
		branchCriteriaBeforeOpenCommit = versionControlHelper.getBranchCriteriaBeforeOpenCommit(commit);
	}

	protected void setCommit(Commit commit) {
		this.commit = commit;
		branchCriteriaBeforeOpenCommit = versionControlHelper.getBranchCriteriaBeforeOpenCommit(commit);
	}

	@Override
	public void loadingComponentsCompleted() {
		completeImportCommit();
	}

	void completeImportCommit() {
		persistBuffers.forEach(PersistBuffer::flush);
		commit.markSuccessful();
		commit.close();
	}

	@Override
	public void newConceptState(String conceptId, String effectiveTime, String active, String moduleId, String definitionStatusId) {
		Integer effectiveTimeI = getEffectiveTimeI(effectiveTime);
		final Concept concept = new Concept(conceptId, effectiveTimeI, isActive(active), moduleId, definitionStatusId);
		if (effectiveTimeI != null) {
			concept.release(effectiveTimeI);
		}
		conceptPersistBuffer.save(concept);
	}

	@Override
	public void newRelationshipState(String id, String effectiveTime, String active, String moduleId, String sourceId, String destinationId,
			String relationshipGroup, String typeId, String characteristicTypeId, String modifierId) {

		Integer effectiveTimeI = getEffectiveTimeI(effectiveTime);
		final Relationship relationship = new Relationship(id, effectiveTimeI, isActive(active), moduleId, sourceId,
				destinationId, Integer.parseInt(relationshipGroup), typeId, characteristicTypeId, modifierId);
		if (effectiveTime != null) {
			relationship.release(effectiveTimeI);
		}

		if (statedRelationshipsToSkip != null
				&& relationship.getCharacteristicTypeId().equals(Concepts.STATED_RELATIONSHIP)
				&& statedRelationshipsToSkip.contains(parseLong(relationship.getId()))) {
			// Do not persist relationship
			return;
		}

		relationshipPersistBuffer.save(relationship);
	}

	@Override
	public void newDescriptionState(String id, String effectiveTime, String active, String moduleId, String conceptId, String languageCode,
			String typeId, String term, String caseSignificanceId) {

		Integer effectiveTimeI = getEffectiveTimeI(effectiveTime);
		final Description description = new Description(id, effectiveTimeI, isActive(active), moduleId, conceptId, languageCode, typeId, term, caseSignificanceId);
		if (effectiveTimeI != null) {
			description.release(effectiveTimeI);
		}
		descriptionPersistBuffer.save(description);
	}

	@Override
	public void newReferenceSetMemberState(String[] fieldNames, String id, String effectiveTime, String active, String moduleId, String refsetId,
			String referencedComponentId, String... otherValues) {

		Integer effectiveTimeI = getEffectiveTimeI(effectiveTime);
		ReferenceSetMember member = new ReferenceSetMember(id, effectiveTimeI, isActive(active), moduleId, refsetId, referencedComponentId);
		for (int i = MEMBER_ADDITIONAL_FIELD_OFFSET; i < fieldNames.length; i++) {
			member.setAdditionalField(fieldNames[i], otherValues[i - MEMBER_ADDITIONAL_FIELD_OFFSET]);
		}
		if (effectiveTime != null) {
			member.release(effectiveTimeI);
		}
		memberPersistBuffer.save(member);
	}

	private Integer getEffectiveTimeI(String effectiveTime) {
		return effectiveTime != null && !effectiveTime.isEmpty() && EFFECTIVE_DATE_PATTERN.matcher(effectiveTime).matches() ? Integer.parseInt(effectiveTime) : null;
	}

	Integer getMaxEffectiveTime() {
		return maxEffectiveTimeCollector.getMaxEffectiveTime();
	}

	protected BranchService getBranchService() {
		return branchService;
	}

	private boolean isActive(String active) {
		return "1".equals(active);
	}

	private abstract class PersistBuffer<E extends Entity> {

		private List<E> entities = new ArrayList<>();

		PersistBuffer() {
			persistBuffers.add(this);
		}

		synchronized void save(E entity) {
			entities.add(entity);
			if (entities.size() >= FLUSH_INTERVAL) {
				flush();
			}
		}

		synchronized void flush() {
			persistCollection(entities);
			entities.clear();
		}

		abstract void persistCollection(Collection<E> entities);

	}

}

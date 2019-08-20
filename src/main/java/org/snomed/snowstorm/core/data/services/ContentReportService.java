package org.snomed.snowstorm.core.data.services;

import com.fasterxml.jackson.annotation.JsonView;
import io.kaicode.elasticvc.api.BranchCriteria;
import io.kaicode.elasticvc.api.VersionControlHelper;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.snomed.snowstorm.core.data.domain.Concept;
import org.snomed.snowstorm.core.data.domain.ConceptMini;
import org.snomed.snowstorm.core.data.domain.Concepts;
import org.snomed.snowstorm.core.data.domain.ReferenceSetMember;
import org.snomed.snowstorm.ecl.ECLQueryService;
import org.snomed.snowstorm.rest.View;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Service;

import java.util.*;

import static io.kaicode.elasticvc.api.ComponentService.LARGE_PAGE;
import static java.lang.Long.parseLong;
import static org.elasticsearch.index.query.QueryBuilders.*;

@Service
public class ContentReportService {

	public static final List<String> LANGUAGE_CODES = Collections.singletonList("en");
	@Autowired
	private VersionControlHelper versionControlHelper;

	@Autowired
	private ElasticsearchOperations elasticsearchOperations;

	@Autowired
	private ECLQueryService eclQueryService;

	@Autowired
	private ConceptService conceptService;

	public List<InactivationTypeAndConceptIdList> findInactiveConceptsWithNoHistoricalAssociationByInactivationType(String branchPath, String conceptEffectiveTime) {
		BranchCriteria branchCriteria = versionControlHelper.getBranchCriteria(branchPath);

		List<InactivationTypeAndConceptIdList> results = new ArrayList<>();

		// Gather ids of inactive concepts
		BoolQueryBuilder boolQueryBuilder = boolQuery()
				.must(branchCriteria.getEntityBranchCriteria(Concept.class))
				.must(termQuery(Concept.Fields.ACTIVE, false));
		if (!Strings.isNullOrEmpty(conceptEffectiveTime)) {
			boolQueryBuilder.must(termQuery(Concept.Fields.EFFECTIVE_TIME, conceptEffectiveTime));
		}
		List<Long> conceptIds = new LongArrayList();
		try (CloseableIterator<Concept> conceptStream = elasticsearchOperations.stream(new NativeSearchQueryBuilder()
				.withQuery(boolQueryBuilder)
				.withFields(Concept.Fields.CONCEPT_ID)
				.withPageable(LARGE_PAGE)
				.build(), Concept.class)) {
			conceptStream.forEachRemaining(concept -> conceptIds.add(concept.getConceptIdAsLong()));
		}
		if (conceptIds.isEmpty()) {
			return results;
		}

		// Gather ids of concepts with historical associations
		List<Long> conceptsWithAssociations = new LongArrayList();
		List<Long> allHistoricalAssociations = eclQueryService.selectConceptIds("<" + Concepts.REFSET_HISTORICAL_ASSOCIATION, branchCriteria, branchPath, true, LARGE_PAGE).getContent();
		try (CloseableIterator<ReferenceSetMember> memberStream = elasticsearchOperations.stream(new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria.getEntityBranchCriteria(ReferenceSetMember.class))
						.must(termQuery(ReferenceSetMember.Fields.ACTIVE, true))
						.must(termsQuery(ReferenceSetMember.Fields.REFSET_ID, allHistoricalAssociations))
						.filter(termsQuery(ReferenceSetMember.Fields.REFERENCED_COMPONENT_ID, conceptIds))
				)
				.withFields(ReferenceSetMember.Fields.REFERENCED_COMPONENT_ID)
				.withPageable(LARGE_PAGE)
				.build(), ReferenceSetMember.class)) {
			memberStream.forEachRemaining(member -> conceptsWithAssociations.add(parseLong(member.getReferencedComponentId())));
		}

		List<Long> conceptsWithoutAssociations = new LongArrayList(conceptIds);
		conceptsWithoutAssociations.removeAll(conceptsWithAssociations);
		if (conceptsWithoutAssociations.isEmpty()) {
			return results;
		}

		// Inactivation indicators
		Map<Long, List<Long>> conceptsByIndicator = new Long2ObjectOpenHashMap<>();
		try (CloseableIterator<ReferenceSetMember> memberStream = elasticsearchOperations.stream(new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria.getEntityBranchCriteria(ReferenceSetMember.class))
						.must(termQuery(ReferenceSetMember.Fields.REFSET_ID, Concepts.CONCEPT_INACTIVATION_INDICATOR_REFERENCE_SET))
						.must(termQuery(ReferenceSetMember.Fields.ACTIVE, true))
						.filter(termsQuery(ReferenceSetMember.Fields.REFERENCED_COMPONENT_ID, conceptsWithoutAssociations))
				)
				.withPageable(LARGE_PAGE)
				.build(), ReferenceSetMember.class)) {
			memberStream.forEachRemaining(member ->
					conceptsByIndicator.computeIfAbsent(
							parseLong(member.getAdditionalField("valueId")), key -> new LongArrayList())
							.add(parseLong(member.getReferencedComponentId())));
		}

		Map<String, ConceptMini> minis = conceptService.findConceptMinis(branchCriteria, conceptsByIndicator.keySet(), LANGUAGE_CODES).getResultsMap();

		for (Long indicator : conceptsByIndicator.keySet()) {
			results.add(new InactivationTypeAndConceptIdList(minis.get(indicator.toString()), new LongOpenHashSet(conceptsByIndicator.get(indicator))));
		}

		List<Long> conceptsWithNoIndicator = new LongArrayList(conceptsWithoutAssociations);
		for (List<Long> longs : conceptsByIndicator.values()) {
			conceptsWithNoIndicator.removeAll(longs);
		}
		if (!conceptsWithNoIndicator.isEmpty()) {
			results.add(new InactivationTypeAndConceptIdList(new ConceptMini("0", LANGUAGE_CODES), new LongOpenHashSet(conceptsWithNoIndicator)));
		}

		return results;
	}

	public static final class InactivationTypeAndConceptIdList {

		private final ConceptMini inactivationIndicator;
		private final Collection<Long> conceptIds;

		public InactivationTypeAndConceptIdList(ConceptMini inactivationIndicator, Collection<Long> conceptIds) {
			this.inactivationIndicator = inactivationIndicator;
			this.conceptIds = conceptIds;
		}

		@JsonView(value = View.Component.class)
		public ConceptMini getInactivationIndicator() {
			return inactivationIndicator;
		}

		@JsonView(value = View.Component.class)
		public Collection<Long> getConceptIds() {
			return conceptIds;
		}
	}
}

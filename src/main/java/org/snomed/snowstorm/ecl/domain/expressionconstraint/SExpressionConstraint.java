package org.snomed.snowstorm.ecl.domain.expressionconstraint;

import io.kaicode.elasticvc.api.BranchCriteria;
import org.snomed.snowstorm.core.data.services.QueryService;
import org.snomed.snowstorm.ecl.domain.RefinementBuilder;
import org.snomed.snowstorm.ecl.domain.SRefinement;
import org.springframework.data.domain.AbstractPageRequest;
import org.springframework.data.domain.Page;

import java.util.Collection;
import java.util.Optional;

public interface SExpressionConstraint extends SRefinement {

	Optional<Page<Long>> select(String path, BranchCriteria branchCriteria, boolean stated, Collection<Long> conceptIdFilter, AbstractPageRequest pageRequest, QueryService queryService);

	Optional<Page<Long>> select(RefinementBuilder refinementBuilder);
}

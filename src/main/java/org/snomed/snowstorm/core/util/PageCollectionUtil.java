package org.snomed.snowstorm.core.util;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.SearchAfterHelper;
import org.springframework.data.elasticsearch.core.SearchAfterPageRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class PageCollectionUtil {

	public static <T> Page<T> listIntersection(List<T> orderedListA, List<T> listB, Pageable pageable, Class<T> typeClass) {
		List<T> fullResultList = orderedListA.stream().filter(listB::contains).collect(Collectors.toList());
		return listToPage(fullResultList, pageable, typeClass);
	}

	public static <T> Page<T> listToPage(List<T> fullResultList, Pageable pageable, Class<T> typeClass) {
		List<T> pageOfResults;
		int pageNumber = 0;
		//Are we working with page numbers, or a searchAfter parameter?
		if (pageable instanceof SearchAfterPageRequest) {
			pageNumber = calculatePageNumber(fullResultList, (SearchAfterPageRequest)pageable, typeClass);
		} else {
			pageNumber = pageable.getPageNumber();
		}
		
		//Did we find the page we were looking for?  Return empty results if not
		if (pageNumber == -1) {
			return new PageImpl<T>(new ArrayList<>(), pageable, 0);
		}
		
		pageOfResults = subList(fullResultList, pageNumber, pageable.getPageSize());
		
		if (pageable instanceof SearchAfterPageRequest) {
			//Set our searchAfter token, as long as we've more results to show
			T lastItem = pageOfResults.get(pageOfResults.size()-1);
			String searchAfterToken = SearchAfterHelper.calculateSearchAfterToken(((SearchAfterPageRequest)pageable), lastItem, typeClass);
			((SearchAfterPageRequest)pageable).setSearchAfterToken(searchAfterToken);
		}
		
		return new PageImpl<T>(pageOfResults, pageable, fullResultList.size());
	}

	public static <T> List<T> subList(List<T> wholeList, int pageNumber, int pageSize) {
		int offset = pageNumber * pageSize;
		int limit = (pageNumber + 1) * pageSize;

		if (offset >= wholeList.size()) {
			return Collections.emptyList();
		}
		if (limit > wholeList.size()) {
			limit = wholeList.size();
		}

		return wholeList.subList(offset, limit);
	}

	private static <T> int calculatePageNumber(List<T> wholeList, SearchAfterPageRequest pageable, Class<T> typeClass) {
		//If we don't have a searchAfter token, we can just return the first 'limit' items
		if (pageable.getSearchAfterToken() == null) {
			return 0;
		}
		
		//What is our sort criteria for these objects? Recover those values to use for comparison
		Method[] sortFieldAccessors = SearchAfterHelper.getSortFields(pageable, typeClass);
		
		//And what do we want those values to be?
		Object[] searchValues = SearchAfterHelper.fromSearchAfterToken(pageable.getSearchAfterToken());
		
		if (searchValues.length != sortFieldAccessors.length) {
			throw new IllegalArgumentException("Sort field count does not equal count of expected values");
		}
		
		//Work through the full results until we find our searchAfter values
		boolean searchAfterCriteriaMet = false;
		int currentPage = 0;
		int positionInPage = 0;
		for (T item : wholeList) {
			if (matchesSearchCriteria(item, sortFieldAccessors, searchValues)) {
				searchAfterCriteriaMet = true;
			}
				
			positionInPage++;
			if (positionInPage >= pageable.getPageSize()) {
				positionInPage = 0;
				currentPage++;
			}
			
			if (searchAfterCriteriaMet) {
				break;
			}
		}
		return searchAfterCriteriaMet ? currentPage : -1;
	}

	private static <T> boolean matchesSearchCriteria(T item, Method[] sortFieldAccessors, Object[] searchValues) {
		for (int i=0; i<sortFieldAccessors.length; i++) {
			try {
				Object value = sortFieldAccessors[i].invoke(item);
				if (!value.toString().equals(searchValues[i].toString())) {
					return false;
				}
			} catch (IllegalArgumentException | InvocationTargetException | IllegalAccessException e) {
				throw new IllegalArgumentException("Unable to access " + sortFieldAccessors[i].getName());
			}
		}
		return true;
	}

}

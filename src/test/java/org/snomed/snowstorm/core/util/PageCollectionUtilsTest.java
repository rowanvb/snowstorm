package org.snomed.snowstorm.core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.SearchAfterHelper;
import org.springframework.data.elasticsearch.core.SearchAfterPageRequest;

public class PageCollectionUtilsTest {
	
	List<Car> testData;
	
	private static final int PAGE_SIZE = 4;
	
	@Before
	public void setup() {
		testData = new ArrayList<>();
		testData.add(new Car("BMW", "3-Series"));
		testData.add(new Car("Vauxhall", "Astra"));
		testData.add(new Car("Renault", "Laguna"));
		testData.add(new Car("Volkswagen", "Golf"));
		testData.add(new Car("Mazda", "5"));
		testData.add(new Car("Tesla", "Model-X"));
		testData.add(new Car("Audi", "A4"));
	}
	

	@Test
	public void subListUsingPageNumbers() {
		//Loop through the test data using page numbers
		Pageable pageRequest = PageRequest.of(0, PAGE_SIZE); 
		List<Car> page1 = PageCollectionUtil.subList(testData, pageRequest, Car.class);
		assertEquals(4, page1.size());
		
		pageRequest = PageRequest.of(1, PAGE_SIZE);
		List<Car> page2 = PageCollectionUtil.subList(testData, pageRequest, Car.class);
		assertEquals(3, page2.size());
		
		checkResults(page1, page2);
	}

	@Test
	public void subListUsingSearchAfter() {
		//Loop through the test data using searchAfter
		Pageable pageRequest = SearchAfterPageRequest.of(null, PAGE_SIZE, Sort.by("Make", "Model"));
		List<Car> page1 = PageCollectionUtil.subList(testData, pageRequest, Car.class);
		
		//Reset our searchToken based on the last item recovered
		SearchAfterHelper.populateSearchAfterToken(page1, pageRequest, Car.class);
		List<Car> page2 = PageCollectionUtil.subList(testData, pageRequest, Car.class);
		
		//Reset our searchToken again.  3rd request should return empty data
		SearchAfterHelper.populateSearchAfterToken(page2, pageRequest, Car.class);
		List<Car> page3 = PageCollectionUtil.subList(testData, pageRequest, Car.class);
		assertTrue(page3.isEmpty());
		
		//And it shouldn't set a searchToken - we're at the end of the data
		SearchAfterHelper.populateSearchAfterToken(page3, pageRequest, Car.class);
		assertNull(((SearchAfterPageRequest)pageRequest).getSearchAfterToken());
		
		checkResults(page1, page2, page3);
	}
	
	
	@SafeVarargs
	private final void checkResults(final List<Car>... pages) {
		//Ensure we got all of them
		Set<Car> recovered = new HashSet<>();
		for(List<Car> page : pages) {
			recovered.addAll(page);
		}
		assertEquals(testData.size(), recovered.size());
		
		//And that they exactly match the original list
		Set<Car> originalData = new HashSet<>(testData);
		originalData.removeAll(recovered);
		assertEquals(0, originalData.size());
	}
	
	public class Car {
		String make;
		String model;
		
		Car (String make, String model) {
			this.make = make;
			this.model = model;
		}
		
		public String getMake() {
			return make;
		}
		
		public String getModel() {
			return model;
		}
		
		public boolean equals (Object other) {
			if (other instanceof Car) {
				Car otherCar = (Car)other;
				if (this.make.equals(otherCar.make)) {
					return this.model.equals(otherCar.model);
				}
			}
			return false;
		}
		
		public String toString() {
			return make + " " + model;
		}
	}
}

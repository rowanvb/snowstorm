package org.snomed.snowstorm.rest.pojo;

import com.fasterxml.jackson.annotation.JsonView;
import org.snomed.snowstorm.rest.View;
import org.springframework.data.domain.Page;
import org.springframework.data.elasticsearch.core.SearchAfterPageRequest;

import java.util.Collection;

public class ItemsPage<T> {

	private final Collection<T> items;
	private final long total;
	private final long limit;
	private final Long offset;
	private final String searchAfterToken;

	public ItemsPage(Collection<T> items) {
		this.items = items;
		this.limit = items.size();
		this.total = items.size();
		this.offset = 0L;
		this.searchAfterToken = null;
	}

	public ItemsPage(Collection<T> items, long total) {
		this.items = items;
		this.limit = items.size();
		this.total = total;
		this.offset = 0L;
		this.searchAfterToken = null;
	}

	public ItemsPage(Page<T> page) {
		this.items = page.getContent();
		this.limit = page.getSize();
		this.total = page.getTotalElements();
		if (page.getPageable() instanceof SearchAfterPageRequest) {
			SearchAfterPageRequest sapr = (SearchAfterPageRequest)page.getPageable();
			this.searchAfterToken = sapr.getSearchAfterToken();
			this.offset = null;
		} else {
			this.offset = new Long(page.getNumber() * page.getSize());
			this.searchAfterToken = null;
		}
		
	}

	@JsonView(View.Component.class)
	public Collection<T> getItems() {
		return items;
	}

	@JsonView(View.Component.class)
	public long getTotal() {
		return total;
	}

	@JsonView(View.Component.class)
	public long getLimit() {
		return limit;
	}

	@JsonView(View.Component.class)
	public Long getOffset() {
		return offset;
	}
	
	@JsonView(View.Component.class)
	public String getSearchAfter() {
		return searchAfterToken;
	}
}

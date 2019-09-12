package org.snomed.snowstorm.rest.pojo;

public class CodeSystemUpdateRequest {

	public String name;
	public String countryCode;
	public String defaultLanguageCode;
	public String branchPath;
	public boolean dailyBuildAvailable;

	public CodeSystemUpdateRequest() {
	}

	public CodeSystemUpdateRequest setBranchPath(String branchPath) {
		this.branchPath = branchPath;
		return this;
	}

	public String getName() {
		return name;
	}

	public String getCountryCode() {
		return countryCode;
	}

	public String getDefaultLanguageCode() {
		return defaultLanguageCode;
	}

	public String getBranchPath() {
		return branchPath;
	}

	public boolean isDailyBuildAvailable() {
		return dailyBuildAvailable;
	}
}

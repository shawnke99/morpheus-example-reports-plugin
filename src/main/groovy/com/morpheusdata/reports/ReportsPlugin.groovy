package com.morpheusdata.reports

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission

class ReportsPlugin extends Plugin {

	@Override
	String getCode() {
		return 'custom-bd-report'
	}

	@Override
	void initialize() {
		this.setName("Custom Bd Report")
		this.setDescription("Budget and Cost Morpheus custom reports")
		this.setAuthor("Shawn Ke")
		BudgetCostReportProvider budgetCostReportProvider = new BudgetCostReportProvider(this, morpheus)
		this.pluginProviders.put(budgetCostReportProvider.code, budgetCostReportProvider)
	}

	@Override
	void onDestroy() {
	}

	@Override
	public List<Permission> getPermissions() {
		// Define the available permissions for the report
		Permission permission = new Permission('Custom Bd Reports', 'customBdReports', [Permission.AccessType.none, Permission.AccessType.full])
		return [permission];
	}
}

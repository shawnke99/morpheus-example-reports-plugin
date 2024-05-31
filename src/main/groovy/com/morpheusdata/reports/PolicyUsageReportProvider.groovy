package com.morpheusdata.reports

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.*
import com.morpheusdata.model.Account
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportType
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.response.ServiceResponse
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j
import java.sql.Connection
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.morpheusdata.core.data.*

@Slf4j
class PolicyUsageReportProvider extends AbstractReportProvider {
	Plugin plugin
	MorpheusContext morpheusContext

	PolicyUsageReportProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		morpheusContext
	}

	@Override
	Plugin getPlugin() {
		plugin
	}

	@Override
	String getCode() {
		'custom-report-policy-usage-example'
	}

	@Override
	String getName() {
		'Custom Report Policy Usage Example'
	}

	 ServiceResponse validateOptions(Map opts) {
		 return ServiceResponse.success()
	 }


	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		ViewModel<String> model = new ViewModel<String>()
		model.object = reportRowsBySection
		getRenderer().renderTemplate("hbs/customPolicyUsageExample", model)
	}

	@Override
	ContentSecurityPolicy getContentSecurityPolicy() {
		def csp = new ContentSecurityPolicy()
		csp
	}

	void process(ReportResult reportResult) {
		// Update the status of the report (generating) - https://developer.morpheusdata.com/api/com/morpheusdata/model/ReportResult.Status.html
		morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.generating).blockingAwait();
		Long displayOrder = 0
		String policyScope
		String roleId
		String currentValue
		String maxValue
		String utilizationPercentage
		String utilizationColor
		def policyData = []
		def accountMappings = [:]
		List<GroovyRowResult> policyAcountMappings = []
		List<GroovyRowResult> dbPolicies = []
		List<GroovyRowResult> accounts = []
		List<GroovyRowResult> instances = []
		List<GroovyRowResult> users = []
		List<GroovyRowResult> roles = []
		List<GroovyRowResult> groups = []
		List<GroovyRowResult> clouds = []

		Connection dbConnection

		def userInstanceData = [:]
		def cloudInstanceData = [:]
		def groupInstanceData = [:]
		def roleInstanceData = [:]
		def globalInstanceData = 0
		def usageOutPayload = [:]
		def obj = []
		try {
			// Create a read-only database connection
			dbConnection = morpheus.report.getReadOnlyDatabaseConnection().blockingGet()
			policyAcountMappings = new Sql(dbConnection).rows("SELECT * FROM policy_account;")
			dbPolicies = new Sql(dbConnection).rows("SELECT policy.id, policy.name, policy.owner_id, policy.enabled, policy.description, policy.ref_type, policy.ref_id, policy_type.code AS policy_type_code FROM policy LEFT JOIN policy_type ON policy.policy_type_id = policy_type.id where policy.enabled = 1;")
			accounts = new Sql(dbConnection).rows("SELECT * FROM account;")
			instances = new Sql(dbConnection).rows("SELECT * FROM instance;")
			users = new Sql(dbConnection).rows("SELECT user.id, user.username, user_role.role_id, user.account_id FROM user LEFT JOIN user_role ON user.id = user_role.user_id;")
			groups = new Sql(dbConnection).rows("SELECT * FROM compute_site;")
			clouds = new Sql(dbConnection).rows("SELECT * FROM compute_zone;")
			roles = new Sql(dbConnection).rows("SELECT * FROM role;")
		} finally {
			// Close the database connection
			morpheus.report.releaseDatabaseConnection(dbConnection)
		}
		
		def dataPolicies = morpheusContext.async.policy.list(
			new DataQuery().withFilters(
			)
		).toList().blockingGet()
		dataPolicies.each { dataPolicy ->
		   println "POLICY: ${dataPolicy.name}"
		   println "POLICY CONFIG: ${dataPolicy.getConfigMap()}"
		}
		// Seed payload
		users.each { user ->
			userInstanceData[user.id] = 0
		}
		roles.each { role ->
			roleInstanceData[role.id.toString()] = 0
		}
		groups.each { group ->
			groupInstanceData[group.id] = 0
		}
		clouds.each { cloud ->
			cloudInstanceData[cloud.id] = 0
		}
		
		instances.each { instance ->
			if (instance.created_by_id){
				userInstanceData[instance.created_by_id]++
			}
			if(instance.provision_zone_id){
				cloudInstanceData[instance.provision_zone_id]++
			}
			if(instance.site_id){
				groupInstanceData[instance.site_id]++
			}
			// Map users to roles
			users.each{ user ->
				if(user.id == instance.created_by_id){
					roleId = user.role_id.toString()
				}
			}
			roleInstanceData[roleId]++
			globalInstanceData++
		}
		usageOutPayload["user"] = userInstanceData
		usageOutPayload["role"] = roleInstanceData
		usageOutPayload["cloud"] = cloudInstanceData
		usageOutPayload["group"] = groupInstanceData
		usageOutPayload["global"] = globalInstanceData
		accounts.each{ account ->
		  accountMappings[account.id] = account.name
		}
		
		dbPolicies.each{
			def payload = [:]
			payload["name"] = it.name
			payload["enabled"] = it.enabled
			payload["id"] = it.id
			payload["refType"] = scopeMapping(it.ref_type)
			payload["refId"] = it.ref_id
			payload["policyTypeCode"] = it.policy_type_code
			payload["accountIds"] = []
			payload["tenantId"] = it.owner_id
			policyData << payload
		}
		def account = new Account(id: 1)
		List<Policy> outpolicies = []
        def policies = morpheusContext.getPolicy().listAllByAccountAndEnabled(account,true).blockingSubscribe { outpolicies << it }
		outpolicies.each{
			policyData.each{ pData ->
				if (pData["name"] == it.name){
					pData["policy"] = it.getConfigMap()
				}
			}
		}
		policyData.each{ pData ->
			if (pData["policyTypeCode"] == "maxVms"){
				def policyTenant = pData["tenantId"]
				log.info "Tenant ID: ${policyTenant}"
				log.info "Report Tenant ID: ${reportResult.account.id}"
				if (policyTenant == reportResult.account.id){
					policyAcountMappings.each{ pMap ->
						if (pMap.policy_accounts_id == pData["id"]){
							pData["accountIds"] << accountMappings[pMap.account_id]
						}
					}
					log.info "Policy Input: ${pData} - ${usageOutPayload}"
					obj = evaluatePolicy(pData,usageOutPayload)
					println "Policy Utilization:${obj}"
					pData["currentValue"] = obj[0]
					pData["maxValue"] = obj[1]
					pData["utilizationPercentage"] = obj[2]
					// Evaluate the utilization percentage to properly display the progress bar color
					if (pData["utilizationPercentage"] >= 90){
						utilizationColor = "red"
					} else if (pData["utilizationPercentage"] >= 70 && pData["utilizationPercentage"] < 90 ){
						utilizationColor = "yellow"
					} else {
						utilizationColor = "green"
					}
					if (pData["currentValue"] == null){
						currentValue = ""
					} else {
						currentValue = pData["currentValue"]
					}
					if (pData["maxValue"] == null){
						maxValue = ""
					} else {
						maxValue = pData["maxValue"]
					}
					utilizationPercentage = pData["utilizationPercentage"]
					switch(pData["policyTypeCode"]) {
						case "maxVms":
							pData["imagesrc"] = "/assets/policy-type/maxVms-e22a806cb948e50cc744654717c1c974.svg"
							break;
						case "maxHosts":
							pData["imagesrc"] = "/assets/policy-type/maxHosts-d4905b641049a3c93ad9aecb3879b5f8.svg"
							break;
						case "maxCores":
							pData["imagesrc"] = "/assets/policy-type/maxCores-cb1033ae8a028572c2bc3a44b1c3f200.svg"
							break;
						case "maxMemory":
							pData["imagesrc"] = "https://grtmorpheus01/assets/policy-type/maxMemory-57118f5da51c2eec78ad9a39680660fa.svg"
							break;
						default:
							break;
					}
					Map<String,Object> data = [name: pData["name"], scope: pData["refType"], policyType: pData["policyTypeCode"], config: pData["policy"], accounts: pData["accountIds"], currentValue: currentValue, maxValue: maxValue, utilizationPercentage: utilizationPercentage, utilizationColor: utilizationColor, imageSrc: pData["imagesrc"] ]
					ReportResultRow resultRowRecord = new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: displayOrder++, dataMap: data)
					morpheus.report.appendResultRows(reportResult,[resultRowRecord]).blockingGet()
				}
			}
		}

        morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.ready).blockingAwait();
	}

	def scopeMapping(policyType){
		switch(policyType) {            					
				case null: 
					return  "global"
				case "ComputeSite": 
					return  "group"
				case "ComputeZone":
					return  "cloud"
				case "User":
					return  "user"
				case "Network":
					return  "network"	
			}
	}
	
	def evaluatePolicy(policyInfo,usagePayload){
		def policy_type = policyInfo["policyTypeCode"]
		def policy_value = policyInfo["policy"][policy_type]
		def rtype = policyInfo["refType"]

		// Evaluate Master Tenant definition


		if (policyInfo["refType"] == "global" || policyInfo["refType"] == null){
			if (policy_type == "maxVms"){
				def data = usagePayload["global"]
				def percentage = (data.toInteger() / policy_value.toInteger() * 100)
				println percentage
				return [data,policy_value,percentage]
			}
		}

		// Evaluate User Association
		if (policyInfo["refType"] == "user"){
			def userId = policyInfo["refId"]
			if (policy_type == "maxVms"){
				def data = usagePayload["user"][userId]
				log.info "User Policy Data ${data}"
				def percentage = (data.toInteger() / policy_value.toInteger() * 100)
				println percentage
				return [data,policy_value,percentage]
			}
		}
		
		// Evaluate Group Association
		if (policyInfo["refType"] == "group"){
			def groupId = policyInfo["refId"]
			if (policy_type == "maxVms"){
				def data = usagePayload["group"][groupId]
				log.info "Group Policy Data ${data}"
				def percentage = (data.toInteger() / policy_value.toInteger() * 100)
				println percentage
				return [data,policy_value,percentage]
			}
		}
		if (policyInfo["refType"] == "cloud"){
			def cloudId = policyInfo["refId"]
			if (policy_type == "maxVms"){
				def data = usagePayload["cloud"][cloudId]
				log.info "Cloud Policy Data ${data}"
				def percentage = (data.toInteger() / policy_value.toInteger() * 100)
				println percentage
				return [data,policy_value,percentage]
			}
		}
		return ["","",""]
	}

	// https://developer.morpheusdata.com/api/com/morpheusdata/core/ReportProvider.html#method.summary
	// The description associated with the custom report
	 @Override
	 String getDescription() {
		 return "Morpheus policy usage custom report"
	 }

	// The category of the custom report
	 @Override
	 String getCategory() {
		 return 'inventory'
	 }

	 @Override
	 Boolean getOwnerOnly() {
		 return false
	 }

	 @Override
	 Boolean getMasterOnly() {
		 return true
	 }

	 @Override
	 Boolean getSupportsAllZoneTypes() {
		 return true
	 }

	// https://developer.morpheusdata.com/api/com/morpheusdata/model/OptionType.html
	 @Override
	 List<OptionType> getOptionTypes() {}
}

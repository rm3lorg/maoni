/*
 * Copyright (c) 2017 Armel Soro
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.rm3l.maoni.jira

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import khttp.extensions.fileLike
import khttp.post
import khttp.structures.files.FileLike
import org.jetbrains.anko.*
import org.json.JSONObject
import org.rm3l.maoni.common.contract.Listener
import org.rm3l.maoni.common.model.Feedback
import org.rm3l.maoni.jira.android.AndroidBasicAuthorization

/**
 * Callback for Maoni that takes care of sending the Feedback as a Jira issue for the specified repo.
 * <p>
 * Written in Kotlin for conciseness
 */
const val USER_AGENT = "maoni-jira (v2.4.0-rc1)"
const val APPLICATION_JSON = "application/json"

@Suppress("unused")
open class MaoniJiraListener(
        val context: Context,
        val debug: Boolean = false,

        val jiraServerRestApiBaseUrl: String,
        val jiraReporterUsername: String,
        val jiraReporterPassword: String,

        val jiraProjectKey: String,
        val jiraIssueSummaryPrefix: String? = "Maoni",
        val jiraIssueDescriptionPrefix: String? = null,
        val jiraIssueDescriptionSuffix: String? = null,
        val jiraIssueType: String? = null,
        val jiraIssueAssignee: String? = null,
        val jiraIssueReporter: String? = null,
        val jiraIssuePriorityId: String? = null,
        val jiraIssueCustomFieldsMap: Map<String, String>? = null,

        val waitDialogTitle: String = "Please hold on...",
        val waitDialogMessage: String = "Submitting your feedback to JIRA Project: $jiraProjectKey ...",
        val successToastMessage: String = "Thank you for your feedback!",
        val failureToastMessage: String = "An error happened - please try again later"
) : Listener, AnkoLogger {

    override fun onSendButtonClicked(feedback: Feedback?): Boolean {
        debug {"onSendButtonClicked"}

        val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetworkInfo: NetworkInfo? = connectivityManager.activeNetworkInfo
        val isConnected = activeNetworkInfo ?.isConnectedOrConnecting ?: false
        if (!isConnected) {
            context.longToast("An Internet connection is required to send your feedback")
            return false
        }

        val jiraIssueUrl = "$jiraServerRestApiBaseUrl/issue"

        val jiraIssueSummaryPrefix =
                if (jiraIssueSummaryPrefix != null) "[$jiraIssueSummaryPrefix] " else ""
        val jiraIssueDescriptionPrefix =
                if (jiraIssueDescriptionPrefix != null) "$jiraIssueDescriptionPrefix\n" else ""
        val jiraIssueDescriptionSuffix =
                if (jiraIssueDescriptionSuffix != null) "$jiraIssueDescriptionSuffix\n" else ""

        val progressDialog = context.indeterminateProgressDialog(title = waitDialogTitle, message = waitDialogMessage)
        progressDialog.show()

        val deviceAndAppInfo = feedback
                ?.deviceAndAppInfoAsHumanReadableMap
                ?.filter { (_, value) -> value != null }
                ?.map { (key,value) -> "- $key : $value" }
                ?.joinToString (separator = "\n")
                ?: ""
        val feedbackMessage = feedback ?.userComment ?: ""

        val fieldsMap = mutableMapOf(
                "project" to mapOf("key" to jiraProjectKey),
                "summary" to "${jiraIssueSummaryPrefix}New Feedback",
                "description" to jiraIssueDescriptionPrefix +
                        "$feedbackMessage" +
                        "\n$jiraIssueDescriptionSuffix" +
                        "\n\n**Context**" +
                        "\n$deviceAndAppInfo",
                "issuetype" to mapOf("name" to (jiraIssueType ?: "")),
                "assignee" to mapOf("name" to (jiraIssueAssignee ?: "")),
                "reporter" to mapOf("name" to (jiraIssueReporter ?: "")),
                "priority" to mapOf("id" to (jiraIssuePriorityId ?: "")))
        fieldsMap.putAll(jiraIssueCustomFieldsMap?: emptyMap())

        val auth = AndroidBasicAuthorization(jiraReporterUsername, jiraReporterPassword)

        doAsync {
            val issueCreationResponse = post(
                    url = jiraIssueUrl,
                    headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Content-Type" to APPLICATION_JSON,
                            "Accept" to APPLICATION_JSON),
                    auth = auth,
                    json = mapOf("fields" to fieldsMap)
            )
            val statusCode = issueCreationResponse.statusCode
            val responseBody = issueCreationResponse.jsonObject
            if (debug) {
                debug {">>> POST $jiraIssueUrl"}
                debug {"<<< [$statusCode] POST $jiraIssueUrl: \n$responseBody"}
            }
            uiThread {
                when (statusCode) {
                    in 100..399 -> {
                        //Upload attachments, if any
                        doAsync {

                            val issueKey = responseBody.getString("key")
                            val issueAttachmentUrl = "$jiraIssueUrl/$issueKey/attachments"

                            val listOfFiles = mutableListOf<FileLike>()
                            if (feedback != null) {
                                if (feedback.includeScreenshot) {
                                    listOfFiles.add(feedback.screenshotFile.fileLike("screenshot.png"))
                                }
                                if (feedback.includeLogs) {
                                    listOfFiles.add(feedback.logsFile.fileLike("logcat.txt"))
                                }
                            }
                            val attachmentsUploadResponseStatusCode: Int
                            val attachmentsUploadResponseResponseBody: JSONObject?
                            if (!listOfFiles.isEmpty()) {
                                val attachmentsUploadResponse = post(
                                        url = issueAttachmentUrl,
                                        headers = mapOf("X-Atlassian-Token" to "nocheck"),
                                        auth = auth,
                                        files = listOfFiles
                                )
                                attachmentsUploadResponseStatusCode = attachmentsUploadResponse.statusCode
                                attachmentsUploadResponseResponseBody = attachmentsUploadResponse.jsonObject
                                if (debug) {
                                    debug {">>> POST $issueAttachmentUrl"}
                                    debug {"<<< [$attachmentsUploadResponseStatusCode] " +
                                            "POST $issueAttachmentUrl: \n$attachmentsUploadResponseResponseBody"}
                                }
                            } else {
                                attachmentsUploadResponseStatusCode = 201
                                attachmentsUploadResponseResponseBody = null
                            }

                            uiThread {
                                progressDialog.cancel()
                                when (attachmentsUploadResponseStatusCode) {
                                    in 100..399 -> {
                                        context.longToast("$successToastMessage. Issue created: $issueKey")
                                    }
                                    else -> {
                                        debug {"responseBody = $attachmentsUploadResponseResponseBody"}
                                        context.longToast(
                                                "$successToastMessage. Issue created: $issueKey, but could not upload attachments: " +
                                                "[$attachmentsUploadResponseStatusCode] $attachmentsUploadResponseResponseBody")
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        progressDialog.cancel()
                        debug {"responseBody = $responseBody"}
                        context.longToast("[$statusCode] $failureToastMessage : $responseBody")
                    }
                }
            }
        }

        return true
    }

    override fun onDismiss() {
        debug {"onDismiss"}
        context.longToast("Dismissed")
    }

}

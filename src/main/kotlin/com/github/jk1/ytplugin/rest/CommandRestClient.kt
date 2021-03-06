package com.github.jk1.ytplugin.rest

import com.github.jk1.ytplugin.commands.model.CommandAssistResponse
import com.github.jk1.ytplugin.commands.model.CommandExecutionResponse
import com.github.jk1.ytplugin.commands.model.YouTrackCommand
import com.github.jk1.ytplugin.commands.model.YouTrackCommandExecution
import com.github.jk1.ytplugin.logger
import com.intellij.openapi.project.Project
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.methods.GetMethod
import org.apache.commons.httpclient.methods.PostMethod
import org.jdom.input.SAXBuilder

class CommandRestClient(override val project: Project) : RestClientTrait, ResponseLoggerTrait {

    private val client: HttpClient get() = createHttpClient(taskManagerComponent.getActiveYouTrackRepository())

    fun assistCommand(command: YouTrackCommand): CommandAssistResponse {
        val method = GetMethod(command.intellisenseCommandUrl)
        val startTime = System.currentTimeMillis()
        try {
            val status = client.executeMethod(method)
            if (status == 200) {
                return CommandAssistResponse(method.responseBodyAsLoggedStream())
            } else {
                throw RuntimeException("HTTP $status: ${method.responseBodyAsLoggedString()}")
            }
        } finally {
            method.releaseConnection()
            logger.debug("Intellisense request to YouTrack took ${System.currentTimeMillis() - startTime} ms")
        }
    }

    fun executeCommand(command: YouTrackCommandExecution): CommandExecutionResponse {
        val method = PostMethod(command.executeCommandUrl)
        val startTime = System.currentTimeMillis()
        try {
            val status = client.executeMethod(method)
            if (status != 200) {
                val string = method.responseBodyAsLoggedStream()
                val element = SAXBuilder(false).build(string).rootElement
                if ("error" == element.name) {
                    return CommandExecutionResponse(errors = listOf(element.text))
                } else {
                    return CommandExecutionResponse(messages = listOf(element.text))
                }
            }
            method.responseBodyAsLoggedString()
            return CommandExecutionResponse()
        } finally {
            method.releaseConnection()
            logger.debug("Command execution request to YouTrack took ${System.currentTimeMillis() - startTime} ms")
        }
    }

    private val YouTrackCommandExecution.executeCommandUrl: String
        get() {
            with(command) {
                val baseUrl = taskManagerComponent.getActiveYouTrackRepository().url
                val execUrl = "$baseUrl/rest/issue/execute/${session.task.id}"
                var params = "command=${command.urlencoded}&comment=${comment?.urlencoded}&disableNotifications=$silent"
                if (commentVisibleGroup != "All Users") {
                    // 'All Users' shouldn't be passed as a parameter value. Localized YouTracks can't understand that.
                    params = "$params&group=${commentVisibleGroup.urlencoded}"
                }
                return "$execUrl?$params"
            }
        }

    private val YouTrackCommand.intellisenseCommandUrl: String
        get() {
            val baseUrl = taskManagerComponent.getActiveYouTrackRepository().url
            val assistUrl = "$baseUrl/rest/command/underlineAndSuggestAndCommands"
            val result = "$assistUrl?command=${command.urlencoded}&caret=$caret&noIssuesContext=false"
            return if (session.hasEntityId()) {
                "$result&issueIds=${session.compressedEntityId?.urlencoded}"
            } else {
                logger.debug("No persistent id found for ${session.task.id}, command suggests may be imprecise and slow")
                "$result&query=${session.task.id}"
            }
        }
}
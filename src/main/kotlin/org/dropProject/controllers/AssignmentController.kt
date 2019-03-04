/*-
 * ========================LICENSE_START=================================
 * DropProject
 * %%
 * Copyright (C) 2019 Pedro Alves
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.dropProject.controllers

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.RefNotAdvertisedException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.ModelMap
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.dropProject.dao.Assignee
import org.dropProject.dao.Assignment
import org.dropProject.dao.AssignmentACL
import org.dropProject.forms.AssignmentForm
import org.dropProject.repository.*
import org.dropProject.services.AssignmentTeacherFiles
import org.dropProject.services.GitClient
import java.io.File
import java.security.Principal
import java.util.logging.Level
import java.util.logging.Logger
import javax.validation.Valid
import kotlin.collections.ArrayList


@Controller
@RequestMapping("/assignment")
class AssignmentController(
        val assignmentRepository: AssignmentRepository,
        val assigneeRepository: AssigneeRepository,
        val assignmentACLRepository: AssignmentACLRepository,
        val submissionRepository: SubmissionRepository,
        val gitSubmissionRepository: GitSubmissionRepository,
        val gitClient: GitClient,
        val assignmentTeacherFiles: AssignmentTeacherFiles) {

    @Value("\${assignments.rootLocation}")
    val assignmentsRootLocation: String = ""

    val LOG = Logger.getLogger(this.javaClass.name)

    @RequestMapping(value = ["/new"], method = [(RequestMethod.GET)])
    fun getNewAssignmentForm(model: ModelMap): String {
        model["assignmentForm"] = AssignmentForm()
        return "assignment-form"
    }

    @RequestMapping(value = ["/new"], method = [(RequestMethod.POST)])
    fun createOrEditAssignment(@Valid @ModelAttribute("assignmentForm") assignmentForm: AssignmentForm,
                               bindingResult: BindingResult,
                               redirectAttributes: RedirectAttributes,
                               principal: Principal): String {

        if (bindingResult.hasErrors()) {
            return "assignment-form"
        }

        var mustSetupGitConnection = false

        if (assignmentForm.acceptsStudentTests &&
                (assignmentForm.minStudentTests == null || assignmentForm.minStudentTests!! < 1)) {
            LOG.warning("Error: You must require at least one student test")
            bindingResult.rejectValue("acceptsStudentTests", "acceptsStudentTests.atLeastOne", "Error: You must require at least one student test")
            return "assignment-form"
        }

        if (!assignmentForm.acceptsStudentTests && assignmentForm.minStudentTests != null) {
            LOG.warning("If you require ${assignmentForm.minStudentTests} student tests, you must check 'Accepts student tests'")
            bindingResult.rejectValue("acceptsStudentTests", "acceptsStudentTests.mustCheck",
                    "Error: If you require ${assignmentForm.minStudentTests} student tests, you must check 'Accepts student tests'")
            return "assignment-form"
        }

        var assignment : Assignment
        if (!assignmentForm.editMode) {   // create

            // check if it already exists an assignment with this id
            if (assignmentRepository.exists(assignmentForm.assignmentId)) {
                LOG.warning("An assignment already exists with this ID: ${assignmentForm.assignmentId}")
                bindingResult.rejectValue("assignmentId", "assignment.duplicate", "Error: An assignment already exists with this ID")
                return "assignment-form"
            }

            // TODO: verify if there is another assignment connected to this git repository

            val gitRepository = assignmentForm.gitRepositoryUrl!!
            if (!gitRepository.startsWith("git@")) {
                LOG.warning("Invalid git repository url: ${assignmentForm.gitRepositoryUrl}")
                bindingResult.rejectValue("gitRepositoryUrl", "repository.notSSh", "Error: Only SSH style urls are accepted (must start with 'git@')")
                return "assignment-form"
            }

            // check if we can connect to given git repository
            try {
                val directory = File(assignmentsRootLocation, assignmentForm.assignmentId)
                gitClient.clone(gitRepository, directory)
                LOG.info("[${assignmentForm.assignmentId}] Successfuly cloned ${gitRepository} to ${directory}")
            } catch (e: Exception) {
                LOG.severe("[${assignmentForm.assignmentId}] Error cloning ${gitRepository} - ${e}")
                if (e.message.orEmpty().contains("Invalid remote: origin") || e.message.orEmpty().contains("Auth fail")) {
                    // probably will need authentication
                    mustSetupGitConnection = true
                    LOG.info("[${assignmentForm.assignmentId}] will redirect to setup-git")
//                    bindingResult.rejectValue("gitRepositoryUrl", "repository.invalid", "Error: Git repository is invalid or inexistent")
                } else {
                    LOG.warning("[${assignmentForm.assignmentId}] Cloning error is neither 'Invalid remote: origin' " +
                            "or 'Auth fail' : [${e.message.orEmpty()}]")
                    bindingResult.rejectValue("gitRepositoryUrl", "repository.genericError", "Error cloning git repository. " +
                            "Are you sure the url is right?")
                    return "assignment-form"
                }
            }

            val newAssignment = Assignment(id = assignmentForm.assignmentId!!, name = assignmentForm.assignmentName!!,
                    packageName = assignmentForm.assignmentPackage, language = assignmentForm.language!!,
                    dueDate = assignmentForm.dueDate, acceptsStudentTests = assignmentForm.acceptsStudentTests,
                    minStudentTests = assignmentForm.minStudentTests, cooloffPeriod = assignmentForm.cooloffPeriod,
                    submissionMethod = assignmentForm.submissionMethod!!,
                    gitRepositoryUrl = assignmentForm.gitRepositoryUrl!!, ownerUserId = principal.name,
                    gitRepositoryFolder = assignmentForm.assignmentId!!)
            assignmentRepository.save(newAssignment)

            assignment = newAssignment

        } else {   // update

            val existingAssignment = assignmentRepository.getOne(assignmentForm.assignmentId)
                    ?: throw IllegalArgumentException("Trying to update an inexistent assignment")

            if (existingAssignment.gitRepositoryUrl != assignmentForm.gitRepositoryUrl) {
                LOG.warning("[${assignmentForm.assignmentId}] Git repository cannot be changed")
                bindingResult.rejectValue("gitRepositoryUrl", "repository.not-updateable", "Error: Git repository cannot be changed.")
                return "assignment-form"
            }

            val acl = assignmentACLRepository.findByAssignmentId(existingAssignment.id)
            if (principal.name != existingAssignment.ownerUserId && acl.find { it -> it.userId == principal.name } == null) {
                LOG.warning("[${assignmentForm.assignmentId}][${principal.name}] Assignments can only be changed " +
                        "by their ownerUserId (${existingAssignment.ownerUserId}) or authorized teachers")
                throw IllegalAccessError("Assignments can only be changed by their owner or authorized teachers")
            }

            // TODO: check again for assignment integrity

            existingAssignment.name = assignmentForm.assignmentName!!
            existingAssignment.packageName = assignmentForm.assignmentPackage
            existingAssignment.language = assignmentForm.language!!
            existingAssignment.dueDate = assignmentForm.dueDate
            existingAssignment.submissionMethod = assignmentForm.submissionMethod!!
            existingAssignment.acceptsStudentTests = assignmentForm.acceptsStudentTests
            existingAssignment.minStudentTests = assignmentForm.minStudentTests
            existingAssignment.cooloffPeriod = assignmentForm.cooloffPeriod
            assignmentRepository.save(existingAssignment)

            assignment = existingAssignment

            // TODO: Need to rebuild?
        }

        if (!(assignmentForm.acl.isNullOrBlank())) {
            val userIds = assignmentForm.acl!!.split(",")

            // first delete existing to prevent duplicates
            assignmentACLRepository.deleteByAssignmentId(assignmentForm.assignmentId!!)

            for (userId in userIds) {
                val trimmedUserId = userId.trim()
                assignmentACLRepository.save(AssignmentACL(assignmentId = assignmentForm.assignmentId!!, userId = trimmedUserId))
            }
        }

        // first delete all assignees to prevent duplicates
        if (assignmentForm.assignmentId != null) {
            assigneeRepository.deleteByAssignmentId(assignmentForm.assignmentId!!)
        }
        val assigneesStr = assignmentForm.assignees?.split(",").orEmpty().map { it -> it.trim() }
        for (assigneeStr in assigneesStr) {
            if (!assigneeStr.isBlank()) {
                assigneeRepository.save(Assignee(assignmentId = assignment.id, authorUserId = assigneeStr))
            }
        }

        if (mustSetupGitConnection) {
            return "redirect:/assignment/setup-git/${assignmentForm.assignmentId}"
        } else {
            redirectAttributes.addFlashAttribute("message", "Assignment was successfully ${if (assignmentForm.editMode) "updated" else "created"}")
            return "redirect:/assignment/info/${assignmentForm.assignmentId}"
        }
    }

    @RequestMapping(value = ["/info/{assignmentId}"], method = [(RequestMethod.GET)])
    fun getAssignmentDetail(@PathVariable assignmentId: String, model: ModelMap, principal: Principal): String {

        val assignment = assignmentRepository.getOne(assignmentId)
        val assignees = assigneeRepository.findByAssignmentIdOrderByAuthorUserId(assignmentId)
        val acl = assignmentACLRepository.findByAssignmentId(assignmentId)

        if (principal.name != assignment.ownerUserId && acl.find { it -> it.userId == principal.name } == null) {
            throw IllegalAccessError("Assignments can only be accessed by their owner or authorized teachers")
        }

        model["assignment"] = assignment
        model["assignees"] = assignees
        model["acl"] = acl

        // check if it has been setup for git connection and if there is a repository folder
        if (assignment.gitRepositoryPrivKey != null && File(assignmentsRootLocation, assignment.gitRepositoryFolder).exists()) {

            // get git info
            val git = Git.open(File(assignmentsRootLocation, assignment.gitRepositoryFolder))
            val lastCommitInfo = gitClient.getLastCommitInfo(git)

            model["lastCommitInfoStr"] = if (lastCommitInfo != null) lastCommitInfo.toString() else "No commits"
        }

        return "assignment-detail";
    }


    @RequestMapping(value = ["/edit/{assignmentId}"], method = [(RequestMethod.GET)])
    fun getEditAssignmentForm(@PathVariable assignmentId: String, model: ModelMap, principal: Principal): String {

        val assignment = assignmentRepository.getOne(assignmentId)
        val acl = assignmentACLRepository.findByAssignmentId(assignmentId)

        if (principal.name != assignment.ownerUserId && acl.find { it -> it.userId == principal.name } == null) {
            throw IllegalAccessError("Assignments can only be changed by their owner or authorized teachers")
        }

        val assignmentForm = AssignmentForm(assignmentId = assignment.id,
                assignmentName = assignment.name,
                assignmentPackage = assignment.packageName,
                language = assignment.language,
                dueDate = assignment.dueDate,
                submissionMethod = assignment.submissionMethod,
                gitRepositoryUrl = assignment.gitRepositoryUrl,
                acceptsStudentTests = assignment.acceptsStudentTests,
                minStudentTests = assignment.minStudentTests,
                cooloffPeriod = assignment.cooloffPeriod
        )

        val assignees = assigneeRepository.findByAssignmentIdOrderByAuthorUserId(assignmentId)
        if (!assignees.isEmpty()) {
            val assigneesStr = assignees.map { it -> it.authorUserId }.joinToString(",\n")
            assignmentForm.assignees = assigneesStr
        }

        if (!acl.isEmpty()) {
            val otherTeachersStr = acl.map { it -> it.userId }.joinToString(",\n")
            assignmentForm.acl = otherTeachersStr
        }

        assignmentForm.editMode = true

        model["assignmentForm"] = assignmentForm

        return "assignment-form";
    }

    @RequestMapping(value = ["/refresh-git/{assignmentId}"], method = [(RequestMethod.POST)])
    fun refreshAssignmentGitRepository(@PathVariable assignmentId: String,
                                       principal: Principal): ResponseEntity<String> {

        // check that it exists
        val assignment = assignmentRepository.getOne(assignmentId)
        val acl = assignmentACLRepository.findByAssignmentId(assignmentId)

        if (principal.name != assignment.ownerUserId && acl.find { it -> it.userId == principal.name } == null) {
            throw IllegalAccessError("Assignments can only be refreshed by their owner or authorized teachers")
        }

        try {
            LOG.info("Pulling git repository for ${assignmentId}")
            gitClient.pull(File(assignmentsRootLocation, assignment.gitRepositoryFolder), assignment.gitRepositoryPrivKey!!.toByteArray())

            // remove the reportId from all git submissions (if there are any) to signal the student that he should
            // generate a report again
            val gitSubmissionsForThisAssignment = gitSubmissionRepository.findByAssignmentId(assignmentId)
            for (gitSubmission in gitSubmissionsForThisAssignment) {
                gitSubmission.lastSubmissionId = null
                gitSubmissionRepository.save(gitSubmission)
            }

            if (!gitSubmissionsForThisAssignment.isEmpty()) {
                LOG.info("Reset reportId for ${gitSubmissionsForThisAssignment.size} git submissions")
            }

        } catch (re: RefNotAdvertisedException) {
            LOG.warning("Couldn't pull git repository for ${assignmentId}: head is invalid")
            return ResponseEntity("{ \"error\": \"Error pulling from ${assignment.gitRepositoryUrl}. Probably you don't have any commits yet.\"}", HttpStatus.INTERNAL_SERVER_ERROR)
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Couldn't pull git repository for ${assignmentId}", e)
            return ResponseEntity("{ \"error\": \"Error pulling from ${assignment.gitRepositoryUrl}\"}", HttpStatus.INTERNAL_SERVER_ERROR)
        }

        return ResponseEntity("{ \"success\": \"true\"}", HttpStatus.OK);
    }

    @RequestMapping(value = ["/setup-git/{assignmentId}"], method = [(RequestMethod.GET)])
    fun setupAssignmentToGitRepository(@PathVariable assignmentId: String, model: ModelMap, principal: Principal): String {

        val assignment = assignmentRepository.getOne(assignmentId)

        if (principal.name != assignment.ownerUserId) {
            throw IllegalAccessError("Assignments can only be changed by their owner")
        }

        if (assignment.gitRepositoryPubKey == null) {

            // generate key pair
            val (privKey, pubKey) = gitClient.generateKeyPair()

            assignment.gitRepositoryPrivKey = String(privKey)
            assignment.gitRepositoryPubKey = String(pubKey)
            assignmentRepository.save(assignment)
        }

        if (assignment.gitRepositoryUrl.orEmpty().contains("github")) {
            val (username, reponame) = gitClient.getGitRepoInfo(assignment.gitRepositoryUrl)
            model["repositorySettingsUrl"] = "https://github.com/${username}/${reponame}/settings/keys"
        }

        model["assignment"] = assignment

        return "setup-git"
    }

    @RequestMapping(value = ["/setup-git/{assignmentId}"], method = [(RequestMethod.POST)])
    fun connectAssignmentToGitRepository(@PathVariable assignmentId: String, redirectAttributes: RedirectAttributes,
                                         model: ModelMap, principal: Principal): String {

        val assignment = assignmentRepository.getOne(assignmentId)

        if (principal.name != assignment.ownerUserId) {
            throw IllegalAccessError("Assignments can only be changed by their owner")
        }

        if (assignment.gitRepositoryPrivKey == null) {
            LOG.warning("gitRepositoryUrl is null???")
            redirectAttributes.addFlashAttribute("error", "Something went wrong with the credentials generation. Please try again")
            return "redirect:/assignment/setup-git/${assignment.id}"
        }

        run {
            val assignmentFolder = File(assignmentsRootLocation, assignment.gitRepositoryFolder)
            if (assignmentFolder.exists()) {
                assignmentFolder.deleteRecursively()
            }
        }

        val gitRepository = assignment.gitRepositoryUrl
        try {
            val directory = File(assignmentsRootLocation, assignment.gitRepositoryFolder)
            gitClient.clone(gitRepository, directory, assignment.gitRepositoryPrivKey!!.toByteArray())
            LOG.info("[${assignmentId}] Successfuly cloned ${gitRepository} to ${directory}")
        } catch (e: Exception) {
            LOG.info("Error cloning ${gitRepository} - ${e}")
            model["error"] = "Error cloning ${gitRepository} - ${e.message}"
            model["assignment"] = assignment
            return "setup-git"
        }

        // check that the assignment repository is a valid assignment structure
        val errorMsg = assignmentTeacherFiles.checkAssignmentFiles(assignment, principal)
        if (errorMsg != null) {
            assignmentRepository.save(assignment)  // assignment.buildResult was updated
            redirectAttributes.addFlashAttribute("error", errorMsg)
            LOG.info("Assignment has problems: ${errorMsg}")
            return "redirect:/assignment/info/${assignment.id}"
        }

        redirectAttributes.addFlashAttribute("message", "Assignment was successfully created and connected to git repository")
        return "redirect:/assignment/info/${assignment.id}"
    }

    @RequestMapping(value = ["/my"], method = [(RequestMethod.GET)])
    fun listMyAssignments(model: ModelMap, principal: Principal): String {

        val assigments = getMyAssignments(principal, archived = false)

        model["assignments"] = assigments
        model["archived"] = false

        return "teacher-assignments-list"
    }



    @RequestMapping(value = ["/archived"], method = [(RequestMethod.GET)])
    fun listMyArchivedAssignments(model: ModelMap, principal: Principal): String {

        val assigments = getMyAssignments(principal, archived = true)

        model["assignments"] = assigments
        model["archived"] = true

        return "teacher-assignments-list"
    }

    @RequestMapping(value = ["/delete/{assignmentId}"], method = [(RequestMethod.POST)])
    fun deleteAssignment(@PathVariable assignmentId: String, redirectAttributes: RedirectAttributes,
                         principal: Principal): String {

        val assignment = assignmentRepository.getOne(assignmentId)

        if (principal.name != assignment.ownerUserId) {
            throw IllegalAccessError("Assignments can only be changed by their owner")
        }

        if (submissionRepository.countByAssignmentId(assignment.id).toInt() > 0) {
            redirectAttributes.addFlashAttribute("error", "Assignment can't be deleted because it has submissions")
            return "redirect:/assignment/my"
        }

        assignmentRepository.delete(assignmentId)
        assignmentACLRepository.deleteByAssignmentId(assignmentId)

        redirectAttributes.addFlashAttribute("message", "Assignment was successfully deleted")
        return "redirect:/assignment/my"
    }

    @RequestMapping(value = ["/toggle-status/{assignmentId}"], method = [(RequestMethod.GET), (RequestMethod.POST)])
    fun toggleAssignmentStatus(@PathVariable assignmentId: String, redirectAttributes: RedirectAttributes,
                               principal: Principal): String {

        val assignment = assignmentRepository.getOne(assignmentId)
        val acl = assignmentACLRepository.findByAssignmentId(assignmentId)

        if (principal.name != assignment.ownerUserId && acl.find { it -> it.userId == principal.name } == null) {
            throw IllegalAccessError("Assignments can only be changed by their owner or authorized teachers")
        }

        if (assignment.active != true) {

            // check if it has been setup for git connection and if there is a repository folder
            if (!File(assignmentsRootLocation, assignment.gitRepositoryFolder).exists()) {
                redirectAttributes.addFlashAttribute("error", "Can't mark assignment as active since it is not connected to a git repository.")
                return "redirect:/assignment/my"
            }

            val errorMsg = assignmentTeacherFiles.checkAssignmentFiles(assignment, principal)
            if (errorMsg != null) {
                assignmentRepository.save(assignment)  // assignment.buildResult was updated
                LOG.info("Assignment has problems: ${errorMsg}")
                redirectAttributes.addFlashAttribute("error", errorMsg)
                return "redirect:/assignment/my"
            }
        }

        assignment.active = !assignment.active
        assignmentRepository.save(assignment)

        redirectAttributes.addFlashAttribute("message", "Assignment was marked ${if (assignment.active) "active" else "inactive"}")
        return "redirect:/assignment/my"
    }

    @RequestMapping(value = ["/archive/{assignmentId}"], method = [(RequestMethod.POST)])
    fun archiveAssignment(@PathVariable assignmentId: String,
                          redirectAttributes: RedirectAttributes,
                          principal: Principal): String {

        // check that it exists
        val assignment = assignmentRepository.getOne(assignmentId)
        val acl = assignmentACLRepository.findByAssignmentId(assignmentId)

        if (principal.name != assignment.ownerUserId && acl.find { it -> it.userId == principal.name } == null) {
            throw IllegalAccessError("Assignments can only be archived by their owner or authorized teachers")
        }

        assignment.archived = true
        assignmentRepository.save(assignment)

        redirectAttributes.addFlashAttribute("message", "Assignment was archived. You can now find it in the Archived assignments page")
        return "redirect:/assignment/my"

    }

    private fun getMyAssignments(principal: Principal, archived: Boolean): List<Assignment> {
        val assignmentsOwns = assignmentRepository.findByOwnerUserId(principal.name)

        val assignmentsACL = assignmentACLRepository.findByUserId(principal.name)
        val assignmentsAuthorized = ArrayList<Assignment>()
        for (assignmentACL in assignmentsACL) {
            assignmentsAuthorized.add(assignmentRepository.findOne(assignmentACL.assignmentId))
        }

        val assignments = ArrayList<Assignment>()
        assignments.addAll(assignmentsOwns)
        assignments.addAll(assignmentsAuthorized)

        val filteredAssigments = assignments.filter { it.archived == archived }

        for (assignment in filteredAssigments) {
            assignment.numSubmissions = submissionRepository.countByAssignmentId(assignment.id).toInt()
            assignment.numUniqueSubmitters = submissionRepository.findUniqueSubmittersByAssignmentId(assignment.id).toInt()
            assignment.public = !assigneeRepository.existsByAssignmentId(assignment.id)
        }
        return filteredAssigments
    }
}
    
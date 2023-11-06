import groovy.json.*
import jenkins.model.Jenkins

Jenkins jenkins = Jenkins.instance
def stages = [:]
def jiraIntegrationEnabled = Boolean.parseBoolean("${JIRA_INTEGRATION_ENABLED}" as String)
def commitValidateStage = jiraIntegrationEnabled ? ',{"name": "commit-validate"}' : ''
def createJFVStage = jiraIntegrationEnabled ? ',{"name": "create-jira-fix-version"}' : ''
def buildTool = "${BUILD_TOOL}"
def goBuildStage = buildTool.toString() == "go" ? ',{"name": "build"}' : ',{"name": "compile"}'

stages['Code-review-application'] = '[{"name": "gerrit-checkout"}' + "${commitValidateStage}" + goBuildStage +
        ',{"name": "tests"},{"name": "sonar"}]'
stages['Code-review-library'] = '[{"name": "gerrit-checkout"}' + "${commitValidateStage}" +
        ',{"name": "compile"},{"name": "tests"},{"name": "sonar"}]'
stages['Code-review-autotests'] = '[{"name": "gerrit-checkout"}' + "${commitValidateStage}" +
        ',{"name": "tests"},{"name": "sonar"}]'
stages['Code-review-default'] = '[{"name": "gerrit-checkout"}' + "${commitValidateStage}" + ']'

stages['Build-library-maven'] = '[{"name": "checkout"},{"name": "get-version"},{"name": "compile"},' +
        '{"name": "tests"},{"name": "sonar"},{"name": "build"},{"name": "push"}' + "${createJFVStage}" + ',{"name": "git-tag"}]'
stages['Build-library-npm'] = stages['Build-library-maven']
stages['Build-library-gradle'] = stages['Build-library-maven']
stages['Build-library-dotnet'] = '[{"name": "checkout"},{"name": "get-version"},{"name": "compile"},' +
        '{"name": "tests"},{"name": "sonar"},{"name": "push"}' + "${createJFVStage}" + ',{"name": "git-tag"}]'

stages['Build-application-maven'] = '[{"name": "checkout"},{"name": "get-version"},{"name": "compile"},' +
        '{"name": "tests"},{"name": "sonar"},{"name": "build"},{"name": "build-image-from-dockerfile"},' +
        '{"name": "push"}' + "${createJFVStage}" + ',{"name": "git-tag"}]'
stages['Build-application-npm'] = '[{"name": "checkout"},{"name": "get-version"},{"name": "compile"},' +
        '{"name": "tests"},{"name": "sonar"},{"name": "build"},{"name": "build-image-from-dockerfile"},' +
        '{"name": "push"}' + "${createJFVStage}" + ',{"name": "git-tag"}]'
stages['Build-application-gradle'] = stages['Build-application-maven']
stages['Build-application-dotnet'] = '[{"name": "checkout"},{"name": "get-version"},{"name": "compile"},' +
        '{"name": "tests"},{"name": "sonar"},{"name": "build-image-from-dockerfile"},' +
        '{"name": "push"}' + "${createJFVStage}" + ',{"name": "git-tag"}]'
stages['Build-application-go'] = '[{"name": "checkout"},{"name": "get-version"},{"name": "tests"},{"name": "sonar"},' +
        '{"name": "build"},{"name": "build-image-from-dockerfile"}' +
        "${createJFVStage}" + ',{"name": "git-tag"}]'
stages['Build-application-python'] = '[{"name": "checkout"},{"name": "get-version"},{"name": "compile"},{"name": "tests"},{"name": "sonar"},' +
        '{"name": "build-image-from-dockerfile"},{"name": "push"}' + "${createJFVStage}" +
        ',{"name": "git-tag"}]'

stages['Build-application-gitops'] = '[{"name": "checkout"},{"name": "deploy-via-helmfile"}]'
stages['Build-library-gitops'] = '[{"name": "checkout"},{"name": "deploy-via-helmfile"}]'
stages['Build-autotests-gitops'] = '[{"name": "checkout"},{"name": "deploy-via-helmfile"}]'
stages['Build-clustermgmt-gitops'] = '[{"name": "checkout"},{"name": "deploy-via-helmfile"}]'
stages['Build-registry-gitops'] = '[{"name": "checkout"},{"name": "validating-values"},{"name": "registry-integration"},{"name": "deploy-via-helmfile"}]'
stages['Build-registry-group-gitops'] = '[{"name": "checkout"},{"name": "deploy-registry-group"}]'


stages['Create-release'] = '[{"name": "checkout"},{"name": "create-branch"},{"name": "trigger-job"}]'
stages['Delete-release'] = '[{"name": "checkout"},{"name": "delete-registry"}]'
stages['Delete-registry-group'] = '[{"name": "checkout"},{"name": "delete-registry-group"}]'

stages['Create-registry-backup'] = '[{"name": "checkout"},{"name": "create-backup"}]'
stages['Restore-registry'] = '[{"name": "checkout"},{"name": "cleanup-registry-before-restore"},{"name": "restore-registry-bucket"}]'

def buildToolsOutOfTheBox = ["maven","npm","gradle","dotnet","none","go","python"]
def defaultBuild = '[{"name": "checkout"}]'

def codebaseName = "${NAME}"
def gitServerCrName = "${GIT_SERVER_CR_NAME}"
def gitServerCrVersion = "${GIT_SERVER_CR_VERSION}"
def gitCredentialsId = "${GIT_CREDENTIALS_ID ? GIT_CREDENTIALS_ID : 'gerrit-ciuser-sshkey'}"
def repositoryPath = "${REPOSITORY_PATH}"
def edpLibraryStagesVersion = "${EDP_LIBRARY_STAGES_VERSION}"
def edpLibraryPipelinesVersion = "${EDP_LIBRARY_PIPELINES_VERSION}"
def codebaseFolder = jenkins.getItem(codebaseName)
if (codebaseFolder == null) {
    folder(codebaseName)
}

createListView(codebaseName, "Releases")
createReleasePipeline("Create-release-${codebaseName}", codebaseName, stages["Create-release"],
        repositoryPath, gitCredentialsId, gitServerCrName, gitServerCrVersion, jiraIntegrationEnabled, edpLibraryStagesVersion, edpLibraryPipelinesVersion)

if (buildTool.toString().equalsIgnoreCase('none')) {
    return true
}

if (BRANCH) {
    def branch = "${BRANCH}"
    def formattedBranch = "${branch.toUpperCase().replaceAll(/\//, "-")}"
    createListView(codebaseName, formattedBranch)

    def type = "${TYPE}"
    def supBuildTool = buildToolsOutOfTheBox.contains(buildTool.toString())
    def crKey = supBuildTool ? "Code-review-${type}" : "Code-review-default"
    createCiPipeline("Code-review-${codebaseName}", codebaseName, stages[crKey], "CodeReview",
            repositoryPath, gitCredentialsId, branch, gitServerCrName, gitServerCrVersion, edpLibraryStagesVersion, edpLibraryPipelinesVersion)

    def buildKey = "Build-${type}-${buildTool.toLowerCase()}".toString()
    if ( type.equalsIgnoreCase('registry') || type.equalsIgnoreCase('clustermgmt') || type.equalsIgnoreCase('registry-group')) {
        def jobExists = false
        if("${formattedBranch}-Build-${codebaseName}".toString() in Jenkins.instance.getAllItems().collect{it.name})
            jobExists = true

        createCiPipeline("Build-${codebaseName}", codebaseName, stages.get(buildKey, defaultBuild), "Build",
                repositoryPath, gitCredentialsId, branch, gitServerCrName, gitServerCrVersion, edpLibraryStagesVersion, edpLibraryPipelinesVersion)

        if(!type.equalsIgnoreCase('registry-group'))
            createReleaseDeletePipeline("Delete-release-${codebaseName}", codebaseName, stages["Delete-release"],
                repositoryPath, gitCredentialsId, branch, gitServerCrName, gitServerCrVersion, edpLibraryStagesVersion, edpLibraryPipelinesVersion)

        // only needed for registryGroup
        if (type.equalsIgnoreCase('registry-group')) {
            createReleaseDeletePipeline("Delete-release-${codebaseName}", codebaseName, stages["Delete-registry-group"],
                    repositoryPath, gitCredentialsId, branch, gitServerCrName, gitServerCrVersion, edpLibraryStagesVersion, edpLibraryPipelinesVersion)
        }

        // only needed for registry
        Boolean isRegistryBackupEnabled = false
        String schedule
        GString valuesFolderPath = "/tmp/${codebaseName}/deploy-templates"
        GString scriptPath = "${valuesFolderPath}/parse_yaml.sh"
        String yqInstallPath = "/tmp"
        String yqVersion = "v4.35.2"

        //delete the folder of the previously downloaded registry
        def rmProc = new ProcessBuilder( 'sh', '-c', "rm -rf /tmp/${codebaseName}").redirectErrorStream(true).start()
        //wait for the command execution
        rmProc.waitForOrKill(2000)
        //clone registry repository
        new ProcessBuilder( 'sh', '-c', "git clone ${repositoryPath} /tmp/${codebaseName}").redirectErrorStream(true).start().text
        //create the file for script
        def createFileProc = new ProcessBuilder( 'sh', '-c', "touch ${scriptPath}").redirectErrorStream(true).start()
        //wait for the command execution
        createFileProc.waitForOrKill(2000)
        //put the script into the created file
        def putScriptProc = new ProcessBuilder( 'sh', '-c', "echo \"#!/bin/sh\n\n" +
                "function check_link() {\n" +
                "\tlocal file_url=\\\$1\n" +
                "\tlocal http_status_code\n" +
                "\tlocal allowed_status_codes=\\\"200 201 202 203 204 205 206 207 208 226\\\"\n\n" +
                "\thttp_status_code=\\\$(curl -s -o /dev/null -L -w \\\"%{http_code}\\\" \\\"\\\$file_url\\\")\n\n" +
                "\tif [[ \\\$allowed_status_codes =~ \\\$http_status_code ]]; then\n" +
                "\t\treturn 0\n" +
                "\telse\n" +
                "\t\treturn 1\n" +
                "\tfi\n" +
                "}\n\n" +

                "function install_yq() {\n" +
                "\tlocal install_path=\\\$1\n" +
                "\tlocal version=\\\$2\n" +
                "\tlocal file_url=\\\"https://github.com/mikefarah/yq/releases/download/\\\$version/yq_linux_amd64\\\"\n\n" +
                "\tif ! test -f \\\"\\\$install_path/yq\\\"; then\n" +
                "\t\tcd \\\"\\\$install_path\\\" || exit\n" +
                "\t\tif check_link \\\"\\\$file_url\\\"; then\n" +
                "\t\t\twget -q -O yq \\\"\\\$file_url\\\"\n" +
                "\t\t\tchmod +x \\\"\\\$install_path/yq\\\"\n" +
                "\t\telse\n" +
                "\t\t\texit 1\n" +
                "\t\tfi\n" +
                "\tfi\n" +
                "}\n\n" +

                "set -e\n" +
                "install_yq ${yqInstallPath} ${yqVersion}\n" +
                "${yqInstallPath}/yq -i ${valuesFolderPath}/values.yaml\" > ${scriptPath}").redirectErrorStream(true).start()
        putScriptProc.waitForOrKill(2000)
        //make a script executable
        def makeExecProc = new ProcessBuilder( 'sh', '-c', "chmod +x ${scriptPath}").redirectErrorStream(true).start()
        makeExecProc.waitForOrKill(2000)
        //run a script for yaml parsing
        def runScriptProc = new ProcessBuilder( 'sh', '-c', ".${scriptPath}").redirectErrorStream(true).start()
        runScriptProc.waitForOrKill(4000)
        //get a script result
        Integer scriptResult = runScriptProc.exitValue()

        if (scriptResult == 0 ) {
            //get backup parameters from values
            isRegistryBackupEnabled = new ProcessBuilder( 'sh', '-c', "${yqInstallPath}/yq -r '.global.registryBackup.enabled' ${valuesFolderPath}/values.yaml").redirectErrorStream(true).start().text.trim().toBoolean()
            schedule = new ProcessBuilder( 'sh', '-c', "${yqInstallPath}/yq -r '.global.registryBackup.schedule' ${valuesFolderPath}/values.yaml").redirectErrorStream(true).start().text.trim()
            schedule = isRegistryBackupEnabled ? schedule : ""
            //rm temp files
            def rmBackupValuesFilesProc = new ProcessBuilder( 'sh', '-c', "rm -rf /tmp/${codebaseName}").redirectErrorStream(true).start()
            rmBackupValuesFilesProc.waitForOrKill(2000)
        } else {
            throw new Exception("[JENKINS][ERROR]: Something went wrong during the execution of a script to retrieve backup values.")
        }

        if (type.equalsIgnoreCase('registry')) {
            createReleaseBackupPipeline("Create-registry-backup-${codebaseName}", codebaseName, stages["Create-registry-backup"],
                    repositoryPath, gitCredentialsId, branch, gitServerCrName, gitServerCrVersion, edpLibraryStagesVersion, edpLibraryPipelinesVersion,
                    isRegistryBackupEnabled, schedule)

            createReleaseRestorePipeline("Restore-registry-${codebaseName}", codebaseName, stages["Restore-registry"],
                    repositoryPath, gitCredentialsId, branch, gitServerCrName, gitServerCrVersion, edpLibraryStagesVersion, edpLibraryPipelinesVersion)
        }

        if(!jobExists)
            queue("${codebaseName}/${formattedBranch}-Build-${codebaseName}")
    }
}

def createCiPipeline(pipelineName, codebaseName, codebaseStages, makeAction, repository, credId, watchBranch = "master", gitServerCrName, gitServerCrVersion, edpLibraryStagesVersion, edpLibraryPipelinesVersion) {
    pipelineJob("${codebaseName}/${watchBranch.toUpperCase().replaceAll(/\//, "-")}-${pipelineName}") {
        if(watchBranch == "master" && pipelineName.startsWith("Build"))
            concurrentBuild(false)
        logRotator {
            numToKeep(10)
            daysToKeep(7)
        }
        triggers {
            gerrit {
                events {
                    if (pipelineName.contains("Build"))
                        changeMerged()
                    else
                        patchsetCreated()
                }
                project("plain:${codebaseName}", ["plain:${watchBranch}"])
            }
        }
        definition {
            cps {
                script("@Library([\"edp-library-stages@${edpLibraryStagesVersion}\", \"edp-library-pipelines@${edpLibraryPipelinesVersion}\"]) _ \n${makeAction}()")
                sandbox(true)
                parameters {
                    stringParam("GIT_SERVER_CR_NAME", "${gitServerCrName}", "Name of Git Server CR to generate link to Git server")
                    stringParam("GIT_SERVER_CR_VERSION", "${gitServerCrVersion}", "Version of GitServer CR Resource")
                    stringParam("STAGES", "${codebaseStages}", "Consequence of stages in JSON format to be run during execution")
                    stringParam("GERRIT_PROJECT_NAME", "${codebaseName}", "Gerrit project name(Codebase name) to be build")
                    if (pipelineName.contains("Build"))
                        stringParam("BRANCH", "${watchBranch}", "Branch to build artifact from")
                }
            }
        }
    }
}

def createReleasePipeline(pipelineName, codebaseName, codebaseStages, repository, credId,
                          gitServerCrName, gitServerCrVersion, jiraIntegrationEnabled, edpLibraryStagesVersion, edpLibraryPipelinesVersion) {
    pipelineJob("${codebaseName}/${pipelineName}") {
        logRotator {
            numToKeep(14)
            daysToKeep(30)
        }
        definition {
            cps {
                script("@Library([\"edp-library-stages@${edpLibraryStagesVersion}\", \"edp-library-pipelines@${edpLibraryPipelinesVersion}\"]) _ \nCreateRelease()")
                sandbox(true)
                parameters {
                    stringParam("STAGES", "${codebaseStages}", "")
                    if (pipelineName.contains("Create-release") || pipelineName.contains("Delete-release")) {
                        stringParam("JIRA_INTEGRATION_ENABLED", "${jiraIntegrationEnabled}", "Is Jira integration enabled")
                        stringParam("GERRIT_PROJECT", "${codebaseName}", "")
                        stringParam("RELEASE_NAME", "", "Name of the release(branch to be created)")
                        stringParam("COMMIT_ID", "", "Commit ID that will be used to create branch from for new release. If empty, HEAD of master will be used")
                        stringParam("GIT_SERVER_CR_NAME", "${gitServerCrName}", "Name of Git Server CR to generate link to Git server")
                        stringParam("GIT_SERVER_CR_VERSION", "${gitServerCrVersion}", "Version of GitServer CR Resource")
                        stringParam("REPOSITORY_PATH", "${repository}", "Full repository path")
                    }
                    if (pipelineName.contains("Delete-release"))
                        stringParam("BRANCH", "${watchBranch}", "Branch to build artifact from")
                }
            }
        }
    }
}

def createListView(codebaseName, branchName) {
    listView("${codebaseName}/${branchName}") {
        if (branchName.toLowerCase() == "releases") {
            jobFilters {
                regex {
                    matchType(MatchType.INCLUDE_MATCHED)
                    matchValue(RegexMatchValue.NAME)
                    regex("^Create-release.*")
                }
            }
        } else {
            jobFilters {
                regex {
                    matchType(MatchType.INCLUDE_MATCHED)
                    matchValue(RegexMatchValue.NAME)
                    regex("^${branchName}-(Code-review|Build).*")
                }
            }
        }
        columns {
            status()
            weather()
            name()
            lastSuccess()
            lastFailure()
            lastDuration()
            buildButton()
        }
    }
}

def createReleaseDeletePipeline(pipelineName, codebaseName, codebaseStages, repository, credId, watchBranch = "master", gitServerCrName, gitServerCrVersion, edpLibraryStagesVersion, edpLibraryPipelinesVersion) {
    pipelineJob("${codebaseName}/${pipelineName}") {
        logRotator {
            numToKeep(10)
            daysToKeep(7)
        }
        definition {
            cps {
                script("@Library([\"edp-library-stages@${edpLibraryStagesVersion}\", \"edp-library-pipelines@${edpLibraryPipelinesVersion}\"]) _ \nBuild()")
                sandbox(true)
                parameters {
                    stringParam("GIT_SERVER_CR_NAME", "${gitServerCrName}", "Name of Git Server CR to generate link to Git server")
                    stringParam("GIT_SERVER_CR_VERSION", "${gitServerCrVersion}", "Version of GitServer CR Resource")
                    stringParam("STAGES", "${codebaseStages}", "Consequence of stages in JSON format to be run during execution")
                    stringParam("GERRIT_PROJECT_NAME", "${codebaseName}", "Gerrit project name(Codebase name) to be build")
                    stringParam("BRANCH", "${watchBranch}", "Branch to build artifact from")
                }
            }
        }
    }
}

def createReleaseBackupPipeline(pipelineName, codebaseName, codebaseStages, repository, credId, watchBranch = "master",
                                gitServerCrName, gitServerCrVersion, edpLibraryStagesVersion, edpLibraryPipelinesVersion,
                                isRegistryBackupEnabled, schedule) {
    pipelineJob("${codebaseName}/${pipelineName}") {
        logRotator {
            numToKeep(10)
            daysToKeep(7)
        }
        if (isRegistryBackupEnabled.toBoolean()) {
            triggers {
                cron("TZ=Europe/Kiev\n" + schedule)
            }
        }
        definition {
            cps {
                script("@Library([\"edp-library-stages@${edpLibraryStagesVersion}\", \"edp-library-pipelines@${edpLibraryPipelinesVersion}\"]) _ \nBuild()")
                sandbox(true)
                parameters {
                    stringParam("STAGES", "${codebaseStages}", "Consequence of stages in JSON format to be run during execution")
                    stringParam("GIT_SERVER_CR_NAME", "${gitServerCrName}", "Name of Git Server CR to generate link to Git server")
                    stringParam("GIT_SERVER_CR_VERSION", "${gitServerCrVersion}", "Version of GitServer CR Resource")
                    stringParam("GERRIT_PROJECT_NAME", "${codebaseName}", "Gerrit project name(Codebase name) to be build")
                    stringParam("BRANCH", "${watchBranch}", "Branch to build artifact from")
                }
            }
        }
    }
}


def createReleaseRestorePipeline(pipelineName, codebaseName, codebaseStages, repository, credId, watchBranch = "master", gitServerCrName, gitServerCrVersion, edpLibraryStagesVersion, edpLibraryPipelinesVersion) {
    pipelineJob("${codebaseName}/${pipelineName}") {
        logRotator {
            numToKeep(10)
            daysToKeep(7)
        }
        definition {
            cps {
                script("@Library([\"edp-library-stages@${edpLibraryStagesVersion}\", \"edp-library-pipelines@${edpLibraryPipelinesVersion}\"]) _ \nBuild()")
                sandbox(true)
                parameters {
                    stringParam("GIT_SERVER_CR_NAME", "${gitServerCrName}", "Name of Git Server CR to generate link to Git server")
                    stringParam("GIT_SERVER_CR_VERSION", "${gitServerCrVersion}", "Version of GitServer CR Resource")
                    stringParam("STAGES", "${codebaseStages}", "Consequence of stages in JSON format to be run during execution")
                    stringParam("GERRIT_PROJECT_NAME", "${codebaseName}", "Gerrit project name(Codebase name) to be build")
                    stringParam("BRANCH", "${watchBranch}", "Branch to build artifact from")
                }
            }
        }
    }
}

/*
 * ================================================================
 * BANKING APP — JENKINS CI PIPELINE
 * Phase 4.10: Existing CI/SCA + SonarQube-gated TEST deployment
 * ================================================================
 *
 * Backend:       Java 21 / Spring Boot / Maven
 * Frontend:      React / TypeScript / npm
 * CI Agent:      Amazon Linux 2023 (ec2-linux)
 *
 * Security:
 *   - Gitleaks, Semgrep, Checkov
 *   - OSV-Scanner 2.3.8 (report-only)
 *   - OWASP Dependency-Check 13.0.0 (S3-backed, offline, report-only)
 *   - CycloneDX SBOM 2.9.1
 *   - SonarQube backend and frontend quality gates
 *
 *
 * Enforcement:
 *   OSV/OWASP vulnerabilities: NON-BLOCKING
 *   Scanner execution errors: BLOCKING
 *   Existing security checks: UNCHANGED
 *   SonarQube quality gates: ENFORCED on main
 *
 * Jenkins credential:
 *   nvd-api-key (Secret text)
 * ================================================================
 */

pipeline {
    agent {
        label 'ec2-linux'
    }

    options {
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
    }

    environment {
        FUNDS_IT_ENABLED = 'false'
        CI = 'true'
    }

    stages {

        stage('Checkout Source Code') {
            steps {
                checkout scm

                script {
                    def headCommit = sh(
                        script: 'git rev-parse HEAD',
                        returnStdout: true
                    ).trim()

                    def mainCommit = sh(
                        script: 'git rev-parse refs/remotes/origin/main',
                        returnStdout: true
                    ).trim()

                    // SonarQube Community Edition: only analyze the main commit.
                    // TEST integration is a promotion branch pointing at that exact commit.
                    def branch = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '')
                        .replaceFirst(/^origin\//, '')
                        .replaceFirst(/^refs\/heads\//, '')
                    def isMainCommit = (headCommit == mainCommit)
                    def isTestBranch = (
                        branch == 'integration/test-deployment' ||
                        env.JOB_BASE_NAME == 'banking-app-test-deploy'
                    )    
                    env.SONAR_ANALYSIS_ENABLED = isMainCommit.toString()
                    env.TEST_DEPLOY_ENABLED = (isTestBranch && isMainCommit).toString()

                    echo "Checked-out commit: ${headCommit}"
                    echo "origin/main commit: ${mainCommit}"
                    echo "Jenkins branch: ${branch}"
                    echo "SonarQube analysis enabled: ${env.SONAR_ANALYSIS_ENABLED}"
                    echo "TEST deployment enabled: ${env.TEST_DEPLOY_ENABLED}"

                    if (isTestBranch && !isMainCommit) {
                        error('TEST promotion rejected: integration branch commit does not match origin/main')
                    }
                }

                sh '''
                    set -eu

                    echo "=== Git Commit ==="
                    git rev-parse --short HEAD

                    echo "=== Repository ==="
                    pwd

                    echo "=== Agent ==="
                    hostname
                    whoami
                '''
            }
        }

        stage('Validate Build Tools') {
            steps {
                sh '''
                    set -eu

                    java -version
                    mvn -version
                    node --version
                    npm --version
                    git --version
                    aws --version
                '''
            }
        }

        stage('Prepare Security Scanners') {
            steps {
                sh '''
                    set -eu
                    bash ci/security/install-scanners.sh
                '''
            }
        }

        stage('Security Scanning') {
            steps {
                sh '''
                    set -eu
                    bash ci/security/run-scans.sh
                '''
            }
        }

        stage('Backend Build and Unit Tests') {
            steps {
                dir('application/funds-transfer-service') {
                    sh '''
                        set -eu

                        echo "=== Spring Boot Backend ==="

                        mvn -B -ntp \
                            -DskipITs=true \
                            clean verify

                        echo "=== Verify JaCoCo XML ==="
                        test -s target/site/jacoco/jacoco.xml
                    '''
                }
            }
        }

        stage('Frontend Validation and Build') {
            steps {
                dir('application/banking-ui') {
                    sh '''
                        set -eu

                        echo "=== Install Dependencies ==="
                        npm ci

                        echo "=== TypeScript, Tests and Build ==="
                        npm run verify

                        echo "=== Generate LCOV Coverage ==="
                        npm run test:coverage

                        echo "=== Verify LCOV ==="
                        test -s coverage/lcov.info

                        echo "=== Dependency Security Audit ==="
                        npm audit --audit-level=high
                    '''
                }
            }
        }

        // ========================================================
        // PHASE 4.8 — SOFTWARE COMPOSITION ANALYSIS (SCA)
        // Vulnerability findings are report-only.
        // ========================================================

        stage('Prepare SCA Scanner') {
            steps {
                sh '''
                    set -eu
                    bash ci/security/install-osv.sh
                '''
            }
        }

                stage('Restore NVD Database from S3') {
            steps {
                sh '''
                    set -eu

                    echo "=== Restore Verified NVD Cache ==="
                    bash ci/security/restore-nvd-cache.sh

                    echo "=== Verify Restored Database ==="
                    test -d .ci-tools/dependency-check-data

                    find .ci-tools/dependency-check-data \
                      -type f -name '*.mv.db' -size +0c
                '''
            }
        }

        stage('Software Composition Analysis - Report Only') {
            steps {
                sh '''
                    set -eu

                    echo "=== Run S3-Backed SCA ==="
                    bash ci/security/run-sca.sh
                '''
            }
        }

        // ========================================================
        // SONARQUBE — BACKEND ANALYSIS AND QUALITY GATE
        // ========================================================

        stage('SonarQube Backend Analysis') {
            when {
                expression {
                    env.SONAR_ANALYSIS_ENABLED == 'true'
                }
            }
            steps {
                dir('application/funds-transfer-service') {
                    script {
                        def scannerHome = tool 'sonar-scanner-cli'

                        withSonarQubeEnv(
                            installationName: 'banking-sonarqube',
                            credentialsId: 'sonarqube-backend-token'
                        ) {
                            withEnv(["SCANNER_HOME=${scannerHome}"]) {
                                sh '''
                                    set -eu

                                    test -s target/site/jacoco/jacoco.xml

                                    "$SCANNER_HOME/bin/sonar-scanner" \
                                      -Dsonar.projectKey=banking-funds-transfer-backend \
                                      -Dsonar.projectName=banking-funds-transfer-backend \
                                      -Dsonar.sources=src/main/java \
                                      -Dsonar.tests=src/test/java \
                                      -Dsonar.java.binaries=target/classes \
                                      -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
                                '''
                            }
                        }
                    }
                }
            }
        }

        stage('Backend Quality Gate') {
            when {
                expression {
                    env.SONAR_ANALYSIS_ENABLED == 'true'
                }
            }
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        // ========================================================
        // SONARQUBE — FRONTEND ANALYSIS AND QUALITY GATE
        // ========================================================

        stage('SonarQube Frontend Analysis') {
            when {
                expression {
                    env.SONAR_ANALYSIS_ENABLED == 'true'
                }
            }
            steps {
                dir('application/banking-ui') {
                    script {
                        def scannerHome = tool 'sonar-scanner-cli'

                        withSonarQubeEnv(
                            installationName: 'banking-sonarqube',
                            credentialsId: 'sonarqube-frontend-token'
                        ) {
                            withEnv(["SCANNER_HOME=${scannerHome}"]) {
                                sh '''
                                    set -eu

                                    test -s coverage/lcov.info

                                    "$SCANNER_HOME/bin/sonar-scanner" \
                                      -Dsonar.projectKey=banking-funds-transfer-frontend \
                                      -Dsonar.projectName=banking-funds-transfer-frontend \
                                      -Dsonar.sources=src \
                                      '-Dsonar.exclusions=**/*.test.ts,**/*.test.tsx,**/*.spec.ts,**/*.spec.tsx' \
                                      -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info
                                '''
                            }
                        }
                    }
                }
            }
        }

        stage('Frontend Quality Gate') {
            when {
                expression {
                    env.SONAR_ANALYSIS_ENABLED == 'true'
                }
            }
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Archive Build Artifacts') {
            steps {
                archiveArtifacts(
                    artifacts: 'application/funds-transfer-service/target/*.jar,application/banking-ui/dist/**',
                    fingerprint: true,
                    allowEmptyArchive: false
                )
            }
        }

        // TEST deployment is permitted only after all preceding CI and SonarQube gates pass.
        stage('Publish TEST Artifact') {
            when {
                expression {
                    env.TEST_DEPLOY_ENABLED == 'true' &&
                    env.SONAR_ANALYSIS_ENABLED == 'true'
                }
            }
            steps {
                sh '''
                    set -eu
                    export GIT_COMMIT="$(git rev-parse HEAD)"
                    test -s application/funds-transfer-service/target/funds-transfer-service-1.0.2-SNAPSHOT.jar
                    bash ci/deploy/publish-test-artifact.sh
                    test -s .ci-release/release-prefix.txt
                '''
            }
        }

        stage('Deploy to TEST') {
            when {
                expression {
                    env.TEST_DEPLOY_ENABLED == 'true' &&
                    env.SONAR_ANALYSIS_ENABLED == 'true'
                }
            }
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    sh '''
                        set -eu
                        bash ci/deploy/deploy-test.sh "$(cat .ci-release/release-prefix.txt)"
                    '''
                }
            }
        }
    }

    post {
        success {
            echo 'SUCCESS: Banking APP CI pipeline completed.'
        }

        failure {
            echo 'FAILED: Review the Jenkins console output.'
        }

        always {
            junit(
                testResults: 'application/funds-transfer-service/target/surefire-reports/*.xml',
                allowEmptyResults: true
            )

            archiveArtifacts(
                artifacts: 'security-reports/*.json',
                allowEmptyArchive: true,
                fingerprint: true
            )

            // Phase 4.8: Archive SCA reports and CycloneDX SBOM.
            archiveArtifacts(
                artifacts: 'reports/sca/*.json,application/funds-transfer-service/target/dependency-check-report.*',
                allowEmptyArchive: true,
                fingerprint: true
            )

            cleanWs()
        }
    }
}

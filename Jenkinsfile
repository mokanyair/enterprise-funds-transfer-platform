
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
        SONAR_ANALYSIS_ENABLED = 'false'
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

                    env.SONAR_ANALYSIS_ENABLED =
                        (headCommit == mainCommit).toString()

                    echo "Checked-out commit: ${headCommit}"
                    echo "origin/main commit: ${mainCommit}"
                    echo "SonarQube analysis enabled: ${env.SONAR_ANALYSIS_ENABLED}"
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

            cleanWs()
        }
    }
}

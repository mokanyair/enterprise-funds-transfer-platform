pipeline {
    agent {
        label 'ec2-linux'
    }

    options {
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
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

        stage('Backend Build and Unit Tests') {
            steps {
                dir('application/funds-transfer-service') {
                    sh '''
                        set -eu

                        echo "=== Spring Boot Backend ==="

                        mvn -B -ntp \
                            -DskipITs=true \
                            clean verify
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

                        echo "=== Dependency Security Audit ==="
                        npm audit --audit-level=high
                    '''
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

                junit(
                    testResults: 'application/funds-transfer-service/target/surefire-reports/*.xml',
                    allowEmptyResults: false
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
            cleanWs()
        }
    }
}

pipeline {
    agent { label 'ec2-linux' }

    options {
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    environment {
        FUNDS_IT_ENABLED = 'false'
        CI = 'true'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                sh '''
                    set -eu
                    git rev-parse --short HEAD
                    git status --short
                '''
            }
        }

        stage('Discover Modules') {
            steps {
                sh '''
                    set -eu

                    echo "=== Maven modules ==="
                    find . -maxdepth 4 -name pom.xml \
                        -not -path '*/target/*' -print

                    echo "=== Node modules ==="
                    find . -maxdepth 4 -name package.json \
                        -not -path '*/node_modules/*' -print
                '''
            }
        }

        stage('Backend Build and Unit Tests') {
            steps {
                sh '''
                    set -eu

                    if [ -f pom.xml ]; then
                        mvn -B -ntp -DskipITs=true clean verify
                    elif [ -f backend/pom.xml ]; then
                        mvn -B -ntp -f backend/pom.xml \
                            -DskipITs=true clean verify
                    else
                        echo "ERROR: Backend pom.xml not found"
                        exit 1
                    fi
                '''
            }
        }

        stage('Frontend Validation and Build') {
            steps {
                sh '''
                    set -eu

                    if [ -f frontend/package.json ]; then
                        cd frontend
                    elif [ -f package.json ]; then
                        :
                    else
                        echo "ERROR: Frontend package.json not found"
                        exit 1
                    fi

                    npm ci
                    npm run verify
                    npm audit --audit-level=high
                '''
            }
        }

        stage('Archive Build Artifacts') {
            steps {
                archiveArtifacts(
                    artifacts: '**/target/*.jar,**/dist/**',
                    fingerprint: true,
                    allowEmptyArchive: false
                )

                junit(
                    testResults: '**/target/surefire-reports/*.xml',
                    allowEmptyResults: false
                )
            }
        }
    }

    post {
        success {
            echo 'Banking APP CI validation completed successfully.'
        }

        failure {
            echo 'Banking APP CI failed. Review the failing stage.'
        }

        always {
            cleanWs()
        }
    }
}

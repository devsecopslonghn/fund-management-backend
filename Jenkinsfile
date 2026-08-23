@Library(['company-ci', 'company-cd']) _

// One pipeline per repository: source validation first, then OCI build,
// Trivy scan, immutable Nexus publication, and a component-scoped GitOps tag
// update. The shared libraries resolve endpoints and credentials in Jenkins.
ciPipeline(
    type: 'container',
    application: 'fund-management',
    language: 'java',
    buildSystem: 'maven',
    sonarSources: ['src'],
    securityScans: [
        sonar: false,
        trivy: true,
        codeql: false,
        securityBlock: false,
        sonarProjectKey: 'fund-management-backend'
    ],
    artifactProfile: 'nexus-container-dev',
    images: [
        [name: 'backend', dockerfile: 'Dockerfile']
    ]
)

cdPipeline(
    strategy: 'gitops',
    application: 'fund-management-backend',
    // Existing Jenkins CD profile supplies the trusted Git credential and
    // target branch. The chart and image tag remain in this repository.
    deploymentProfile: 'card-credit-dev',
    gitopsRepository: 'scm',
    valuesFile: 'deploy/chart/values.yaml',
    variables: [imageTag: env.IMAGE_TAG]
)

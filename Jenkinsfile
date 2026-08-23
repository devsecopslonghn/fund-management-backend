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
    deploymentProfile: 'fund-management-backend-dev',
    valuesFile: 'fund-management-backend/values.yaml',
    variables: [imageTag: env.IMAGE_TAG]
)

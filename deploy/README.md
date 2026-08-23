# Application-owned GitOps

The Helm chart and immutable backend image tag are maintained in this
repository. `Jenkinsfile` runs `company-ci` first, then calls `company-cd` with
`gitopsRepository: 'scm'`; CD commits only `chart/values.yaml` in this same
repository. Argo CD tracks `chart/` through the central workload declaration in
`k8s-namepsace-chart`.

The empty initial image tag intentionally emits no Deployment. The namespace
must contain `fund-management-runtime`, `fund-management-admin`, and
`nexus-registry`; these are intentionally not committed to Git.

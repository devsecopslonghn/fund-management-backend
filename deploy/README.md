# Application-owned GitOps

The Helm chart and immutable backend image tag are maintained in this
repository. GitHub Actions runs the reusable Java workflow, publishes the image
to GHCR, and commits the new tag to `chart/values.yaml`. Argo CD tracks
`chart/` through the central workload declaration in `k8s-namepsace-chart`.

The empty initial image tag intentionally emits no Deployment. The namespace
must contain `fund-management-runtime`, `fund-management-admin`, and
`nexus-registry`; these are intentionally not committed to Git.

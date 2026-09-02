{{- define "fund-management-backend.labels" -}}
app.kubernetes.io/name: fund-management
app.kubernetes.io/component: backend
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

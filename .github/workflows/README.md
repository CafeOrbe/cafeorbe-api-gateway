# Pipeline `ci.yml`

`ci` compila `cafeorbe-contracts`, ejecuta `mvn -B verify` y espera el Quality Gate de Sonar. `build-push` publica la imagen en Azure Container Registry y `deploy-qa` actualiza la aplicación en Azure Container Apps y corre el smoke test de `/actuator/health`. `deploy-prod` solo se ejecuta con tags `v*`.

## Secretos

| Nombre | Uso |
| --- | --- |
| `AZURE_CREDENTIALS` | inicio de sesión en Azure |
| `ACR_USERNAME`, `ACR_PASSWORD` | push de la imagen a `vars.ACR_NAME`.azurecr.io |
| `SONAR_TOKEN` | Quality Gate (secreto de organización) |
| `JWT_SECRET` | firma y validación de la sesión |
| `INTERNAL_TOKEN` | credencial interna SEG-02; **el mismo valor en los 6 servicios** |
| `REDIS_PASSWORD` | autenticación con Azure Cache for Redis |
| `OTLP_METRICS_AUTH` | autenticación de métricas OTLP (opcional) |

## Variables

| Nombre | Por defecto | Uso |
| --- | --- | --- |
| `ACR_NAME` | — | registro de imágenes |
| `REDIS_HOST` | — | host de Redis; `deploy-qa` falla si no está definida |
| `REDIS_PORT` | `6380` | puerto TLS |
| `REDIS_SSL` | `true` | TLS |
| `OTLP_METRICS_ENABLED` | `false` | exportar métricas |
| `OTLP_METRICS_URL` | `http://localhost:4318/v1/metrics` | endpoint OTLP |

`deploy-qa` aborta antes de tocar Azure si falta `INTERNAL_TOKEN` o `REDIS_HOST`. El smoke test reintenta hasta 3 minutos por el arranque en frío.

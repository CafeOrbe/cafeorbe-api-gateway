# cafeorbe-api-gateway

Punto único de entrada HTTP. **Arquitectura:** patrón API Gateway (Spring Cloud Gateway), sin capas de dominio. Puerto `8080`.

| Pieza | Dónde |
|---|---|
| Tabla de rutas | `application.yml` → `/api/sesion`, `/api/subastas`, `/api/orbes`, `/api/streaming` |
| Filtro de autenticación | `filters/auth` — valida el JWT y agrega `X-User-Id`, `X-User-Name` (URL-encoded) y `X-User-Role` |
| Errores y timeouts | `filters/errors` — formato uniforme `{status, mensaje, campos}`; 503 si un servicio está caído, 504 si tarda más de 10 s |
| CORS | `globalcors` con `CORS_ORIGINS` (por defecto `http://localhost:5173`) |

- Solo `POST /api/sesion` y los preflight `OPTIONS` son públicos.
- **Las cabeceras `X-User-*` que envíe el cliente se descartan siempre**, así nadie puede suplantar a otro usuario.
- Solo se exponen las rutas del MVP: no hay `/store` ni `/shipping`, y `/internal/**` (consulta de saldo entre servicios) no se enruta.
- El WebSocket **no** pasa por aquí: el navegador se conecta directo al realtime-gateway.

> Los servicios internos confían en las cabeceras `X-User-*`. En producción sus puertos no deben ser accesibles desde fuera de la red interna.

```bash
mvn spring-boot:run      # variables: IDENTITY_URL, AUCTION_URL, WALLET_URL, STREAMING_URL, JWT_SECRET, CORS_ORIGINS
mvn test                 # 7 pruebas contra un servicio interno simulado
```

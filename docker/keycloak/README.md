# Local identity setup

Compose runs Keycloak 26.8.1 on `http://localhost:8082`. The realm export is a
reproducible development fixture, not production identity infrastructure.

```sh
docker compose -f docker/compose.yaml up -d --wait
```

The first boot imports `flowops-realm.json`. Keycloak stores realm changes in the
`keycloak_data` volume. Later boots skip the import if `flowops` already exists;
editing the JSON does not update an existing realm. To apply a changed fixture,
use the admin console to apply the change, or deliberately recreate only the
local Keycloak data volume after stopping its service. Do not delete the separate
`postgres_data` volume: it holds business records.

## Accounts and claim ownership

- `berlin.operator` / `flowops_local_only`: tenant
  `11111111-1111-4111-8111-111111111111`, preserving access to earlier demo records.
- `berlin.viewer` / `flowops_local_only`: same tenant as Berlin, read-only access.
- `hamburg.operator` / `flowops_local_only`: tenant
  `22222222-2222-4222-8222-222222222222`, a separate organization.
- Administration: `http://localhost:8082/admin`, `local-admin` / `local_admin_only`.

All passwords above are public local fixtures. No business rows are seeded.
Registration and password grants are disabled. The managed `tenant_id` user
attribute grants view/edit permission only to administrators; unmanaged attributes
are disabled. Keep those restrictions when creating more users. A signed tenant
claim is only trustworthy if its source cannot be changed by the end user.

The `basic` default client scope supplies the required `sub` claim. Keep it enabled
when customizing the other client scopes. Broad role scope inheritance is disabled.

The `trusted-tenant` mapper copies that admin-managed attribute into access tokens.
The `flowops-api-audience` mapper adds `flowops-api` only to the access-token
audience. The API validates both claims, the issuer, subject, expiry, and signature.
An ID token is for the browser client and must not authenticate API requests.

## Public browser client

`flowops-console` uses authorization code with mandatory PKCE S256, no client
secret, no implicit flow, and no resource-owner password flow. Redirect and logout
URLs are exact root URLs on localhost/127.0.0.1 port 5173. Web origins are limited
to those two origins. Changes to frontend ports or hosting require updating these
allowlists as well as the frontend configuration.

Access tokens expire after five minutes. This first increment intentionally asks
for sign-in again after expiry rather than implementing background refresh; the
provider's SSO session can avoid another password prompt. Logout clears browser
memory and ends the provider session. Already-issued bearer tokens remain usable
until expiration because the API validates JWTs locally, without introspection.

## Configuration and deployment boundary

Defaults: backend `OIDC_ISSUER_URI=http://localhost:8082/realms/flowops`,
`OIDC_AUDIENCE=flowops-api`; frontend
`VITE_OIDC_AUTHORITY=http://localhost:8082/realms/flowops`,
`VITE_OIDC_CLIENT_ID=flowops-console`. The host in the issuer is significant: use
exactly the same value in the frontend and backend. A browser pointed at
127.0.0.1 for Vite may still use localhost for its identity authority.

`KEYCLOAK_PORT` changes the Compose port mapping; when changed, update both issuer
settings too. `OIDC_JWK_SET_URI` can override key discovery while preserving the
configured issuer check. No permissive development authentication mode exists.

Production needs a production Keycloak database, TLS, managed secrets, exact
production redirects/origins, a deliberate user-provisioning process, and a tenant
membership/permission policy. The bundled `start-dev` configuration binds only to
loopback and is not that deployment. Access requires an assigned `flowops-viewer` or `flowops-operator` realm role.
Role removal takes effect on newly issued tokens; existing tokens last until expiry.

Implementation references: [Keycloak container configuration](https://www.keycloak.org/server/containers),
[Keycloak user-profile permissions](https://www.keycloak.org/docs/latest/server_admin/#_user-profile),
[Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html),
and [oidc-client-ts](https://authts.github.io/oidc-client-ts/).

## Application permissions and existing realms

The dedicated mapper writes allowed realm roles into the access-token-only
`flowops_roles` array. `fullScopeAllowed` stays false; the console's explicit role
scope mappings include only `flowops-viewer` and `flowops-operator`. The API ignores
other realm roles, OAuth scopes, and caller-provided permission fields. Operators
can read and write; viewers can only read. The viewer account shares Berlin's data.

For a realm created before this milestone, startup import does not update it. Keep
its business data and users, then apply these changes through the local admin console:

1. In realm **flowops**, create realm roles `flowops-viewer` and `flowops-operator`.
2. Under **Clients → flowops-console → Client scopes → flowops-console-dedicated → Scope**,
   assign both realm roles to the client's permitted role scope mappings. Keep
   **Full scope allowed** off.
3. Under that dedicated scope's **Mappers**, add **User Realm Role**. Name it
   `flowops-application-roles`; token claim name `flowops_roles`; JSON type `String`;
   **Multivalued** on; **Add to access token** on; ID token and userinfo off.
   Leave the realm role prefix empty.
4. Under each operator user's **Role mapping**, assign `flowops-operator`.
5. For a read-only user, assign `flowops-viewer` and set its admin-only `tenant_id`
   to the intended organization. The bundled `berlin.viewer` fixture has Berlin's
   tenant UUID and the same local password as the other fixtures.
6. Sign out and back in to obtain a token with the new roles. The API deliberately
   denies old tokens without application roles rather than granting a fallback.

For a disposable identity database, recreating only Keycloak's volume imports the
complete updated fixture instead. Never remove the business PostgreSQL volume.

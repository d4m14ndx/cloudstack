# Java 21 Compatibility Scout

Date: 2026-05-22
Branch: java21-compat-scout
Base: 78d2c3b3f5

## Scope

This note records the Java 21 compatibility scout findings from
`docs/NON_WEB_BACKEND_CLEANUP_JAVA21_AUDIT.md`. It excludes baseline config and
documentation files owned by the java21-baseline lane.

## Mechanical Fixes Applied

Replaced low-risk deprecated reflective `Class.newInstance()` calls with
`getDeclaredConstructor().newInstance()` where existing code already handled
reflection failures through local configuration/runtime error paths:

- `services/secondary-storage/server/src/main/java/org/apache/cloudstack/storage/resource/NfsSecondaryStorageResource.java`
- `services/secondary-storage/server/src/main/java/org/apache/cloudstack/storage/template/DownloadManagerImpl.java`
- `services/secondary-storage/server/src/main/java/org/apache/cloudstack/storage/template/UploadManagerImpl.java`
- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxy.java`
- `utils/src/main/java/com/cloud/utils/component/ComponentContext.java`
- `core/src/main/java/com/cloud/resource/RequestWrapper.java`
- `server/src/main/java/com/cloud/api/ApiAsyncJobDispatcher.java`
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/LibvirtComputingResource.java`
- `plugins/hypervisors/baremetal/src/main/java/com/cloud/baremetal/manager/BareMetalDiscoverer.java`
- `server/src/main/java/com/cloud/api/auth/APIAuthenticationManagerImpl.java`
- `server/src/main/java/com/cloud/api/ApiAddressVlanResponseServiceImpl.java`
- `framework/db/src/main/java/com/cloud/utils/db/GenericDaoBase.java`
- `engine/schema/src/main/java/com/cloud/upgrade/DatabaseCreator.java`

Remaining deprecated reflective construction sites are test-only:

- `plugins/hypervisors/kvm/src/test/java/com/cloud/hypervisor/kvm/resource/LibvirtVifDriverTest.java`
- `framework/db/src/test/java/com/cloud/utils/db/ElementCollectionTest.java`

Other `newInstance()` hits in Java files are either constructor invocation,
factory APIs such as XML/JAXB factories, `Array.newInstance`, or already use
`getDeclaredConstructor().newInstance()`.

## Internal JDK APIs

These should be handled as dedicated slices because they require behavior-level
replacement choices, not a mechanical rename:

- `services/console-proxy/rdpconsole/src/main/java/rdpclient/ntlmssp/CryptoAlgos.java`
  uses `sun.security.provider.MD4`.
- `services/console-proxy/rdpconsole/src/main/java/streamer/apr/AprSocketWrapperImpl.java`
  imports `sun.security.x509.X509CertImpl`.
- `server/src/main/java/com/cloud/api/ApiDirectDownloadCertificateResponseServiceImpl.java`
  imports `sun.security.x509.X509CertImpl`.
- `server/src/main/java/org/apache/cloudstack/direct/download/DirectDownloadManagerImpl.java`
  imports `sun.security.x509.X509CertImpl`.

## `com.sun.net.httpserver`

Console proxy and Prometheus exporter depend on the JDK HTTP server API:

- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxy.java`
- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxyAjaxImageHandler.java`
- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxyAjaxHandler.java`
- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxyBaseServerFactoryImpl.java`
- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxyThumbnailHandler.java`
- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxyResourceHandler.java`
- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxyCmdHandler.java`
- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxyServerFactory.java`
- `services/console-proxy/server/src/main/java/com/cloud/consoleproxy/ConsoleProxySecureServerFactoryImpl.java`
- `plugins/integrations/prometheus/src/main/java/org/apache/cloudstack/metrics/PrometheusExporterServerImpl.java`

This is not necessarily a Java 21 compile blocker, but it is a portability and
module-surface risk. Treat replacement or containment as a separate server
slice.

## Finalization

Object finalization-style cleanup remains in:

- `engine/orchestration/src/main/java/com/cloud/agent/manager/DirectAgentAttache.java`
- `engine/orchestration/src/main/java/com/cloud/agent/manager/ConnectedAgentAttache.java`
- `framework/db/src/main/java/com/cloud/utils/db/SearchBase.java`
- `framework/db/src/main/java/com/cloud/utils/db/ConnectionConcierge.java`
- `framework/db/src/main/java/com/cloud/utils/db/TransactionLegacy.java`

Search also finds domain methods named `finalize(Network, boolean)` in network
redundancy code; those are not `Object.finalize()` cleanup overrides.

## JVM Flags

`--add-opens`, `--add-exports`, and `-noverify` references remain in baseline
configuration areas and runtime packaging:

- `pom.xml`
- `Dockerfile`
- `developer/pom.xml`
- `plugins/user-authenticators/ldap/pom.xml`
- `packaging/systemd/cloudstack-management.default`
- `packaging/systemd/cloudstack-usage.default`

The baseline-owned files were intentionally not edited in this scout branch.

## Verification Notes

`git diff --check` passed.

Targeted Maven compile was attempted for touched modules:

```bash
mvn -pl core,engine/schema,framework/db,plugins/hypervisors/baremetal,plugins/hypervisors/kvm,server,services/console-proxy/server,services/secondary-storage/server,utils -am -DskipTests compile
```

The sandboxed run failed before the touched source could compile because Maven
could not write tracking files under `~/.m2`. An escalated run reached
`cloud-utils` compilation but failed on a corrupted
`org/aspectj/aspectjweaver/1.8.13/aspectjweaver-1.8.13.jar` with:

```text
Invalid CEN header (invalid zip64 extra data field size)
```

The same error reproduced with a clean temporary Maven repository at
`/private/tmp/cloudstack-java21-scout-m2`, so this is currently a dependency
artifact/repository blocker rather than evidence of a source-level failure in
the scout changes.

## Suggested Next Slices

1. Fix or override the `aspectjweaver:1.8.13` artifact source, then rerun the
   targeted module compile under Java 21.
2. Finish the remaining test-only `Class.newInstance()` replacements.
3. Replace `sun.security.provider.MD4` in RDP NTLM with a supported digest
   implementation.
4. Replace or encapsulate `sun.security.x509.X509CertImpl` certificate parsing
   in direct-download and RDP code.
5. Plan explicit cleanup replacements for `finalize()` users, starting with
   `DirectAgentAttache` and `ConnectedAgentAttache`.

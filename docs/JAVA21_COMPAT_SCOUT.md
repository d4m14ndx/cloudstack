# Java 21 Compatibility Scout

Date: 2026-05-22
Branch: java21-compat-scout
Base: 78d2c3b3f5
Integrated: modernize-2026

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

Resolved during the Java 21 baseline integration:

- `services/console-proxy/rdpconsole/src/main/java/rdpclient/ntlmssp/CryptoAlgos.java`
  now uses Bouncy Castle `MD4Digest` instead of `sun.security.provider.MD4`.
- `services/console-proxy/rdpconsole/src/main/java/streamer/apr/AprSocketWrapperImpl.java`
  now parses the peer certificate with `CertificateFactory` and
  `X509Certificate`.
- `server/src/main/java/com/cloud/api/ApiDirectDownloadCertificateResponseServiceImpl.java`
  now uses the public `X509Certificate` API.
- `server/src/main/java/org/apache/cloudstack/direct/download/DirectDownloadManagerImpl.java`
  now uses the public `X509Certificate` API.

Post-merge source search found no remaining non-web Java references to
`sun.security.*`, `X509CertImpl`, or `sun.security.provider.MD4`.

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

Mockito inline mocking is now handled by the Java 21 baseline. Surefire and
Failsafe resolve `mockito-core` with `maven-dependency-plugin:properties` and
attach it with `-javaagent`, matching Mockito's Java 21 guidance and avoiding
dynamic self-attach failures in focused module tests.

## Verification Notes

`git diff --check` passed.

The original scout branch targeted compile was blocked by the old
`aspectjweaver:1.8.13` artifact. The Java 21 baseline now aligns both
`aspectjtools` and `aspectjweaver` with `${cs.aspectjrt.version}` (`1.9.19`).

Post-merge verification under OpenJDK 21:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -B -ntp install -DskipTests -T4
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -B -ntp -pl plugins/storage/volume/ontap test -T1
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -B -ntp -pl server -Dtest=ApiDirectDownloadCertificateResponseServiceImplTest,DirectDownloadManagerImplTest test -T1
```

All passed.

## Suggested Next Slices

1. Finish the remaining test-only `Class.newInstance()` replacements.
2. Plan explicit cleanup replacements for `finalize()` users, starting with
   `DirectAgentAttache` and `ConnectedAgentAttache`.
3. Decide whether to replace, wrap, or keep the JDK `com.sun.net.httpserver`
   dependency in console proxy and Prometheus exporter.

# Third-party components

- jcifs-ng 2.1.10 (eu.agno3.jcifs): LGPL 2.1 or later. Upstream: https://github.com/AgNO3/jcifs-ng/tree/jcifs-ng-2.1.10 . Corresponding upstream source is included in ThirdParty/source/jcifs-ng-2.1.10.tar.gz; no local changes were made to this library. License is included in app/src/main/assets/licenses/jcifs-ng-LGPL-2.1.txt.
- Bouncy Castle bcprov-jdk18on 1.86: MIT license, included in app/src/main/assets/licenses/bcprov-jdk18on-META-INF_LICENSE.md. Upstream: https://www.bouncycastle.org/ . Explicit version overrides the older transitive dependency.
- SLF4J API 1.7.36: MIT license, included in app/src/main/assets/licenses/slf4j-MIT.txt. Upstream: https://github.com/qos-ch/slf4j/tree/v_1.7.36 .

License copies are bundled inside the APK assets. Application source and Gradle configuration are provided to allow rebuilding with replacement library versions. Development signing keys are not production distribution credentials.

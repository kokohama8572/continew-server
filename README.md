# contiNEW
학교 수업

여러 정체성 상태가 각자의 개인 공간을 쓰면서, 필요할 때 공용 공간에서 메시지와 생활 정보를 나누는 서비스입니다.
이 저장소는 백엔드(Spring Boot)입니다. 기능 정의는 기능 명세서를 따릅니다.

## 문서

- [기능 명세서](spec/continew-feature-spec.md)

## 요구 사항

- JDK 21 이상 (Gradle toolchain이 21을 사용합니다)

## 실행

```bash
./gradlew bootRun
```

- 상태 확인: http://localhost:8080/actuator/health

## 테스트·빌드

```bash
./gradlew test
./gradlew build
```

## 구조

```
src/main/java/com/continew
└── ContiNewApplication.java
```

MVP는 로컬 우선이라 서버는 AI 요약 중계가 중심입니다. DB·인증은 서버 저장 방식을 정한 뒤 추가합니다.

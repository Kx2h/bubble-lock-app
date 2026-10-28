# HTTPS API 전송 준비

ROLE: APP · DOC-REV: 2.0.0 · RUBRIC: R03/R10/R17. `HttpsApiTransport`는 향후 PC-MOBIUS-INTEGRATION의 실제 계약을 연결하기 위한 전송 경계다. 아직 앱 UI나 운영 서비스에서 호출하지 않는다. 이 코드만으로 인증·인가·동의·명령·receipt가 구현되거나 서버 계약이 확정된 것은 아니다.

호출자는 미리 신뢰한 HTTPS origin을 제공하고, 상대 경로와 쿼리, `GET`/`POST`/`PUT`/`DELETE`, 계약에서 정한 헤더와 바이트 본문을 직접 구성한다. token·request ID·correlation·idempotency·API 버전의 이름과 의미는 계약이 정해지면 caller에서 정의한다. GET 본문과 다른 origin, redirect, 경로 traversal, 일부 모호한 인코딩 및 위험한 헤더는 거부한다. 경로의 `%25`도 거부해 이중 디코딩에서 구분자나 상위 경로로 바뀌는 것을 방지하므로 퍼센트 문자를 포함하는 경로는 지원하지 않는다. 쿼리 값은 별도 계약에 따라 caller가 구성한다. 정상·오류 HTTP status와 최대 64 KiB 본문을 반환하고, 같은 이름의 응답 헤더가 반복되면 해당 헤더를 노출하지 않는다. 요청 본문도 64 KiB 이내다. 민감한 URL·헤더·본문·예외를 로그에 남기지 않는 것은 호출자의 책임이다.

프로세스의 `HttpsURLConnection` 기본 socket factory와 hostname verifier를 사용하고 TLS 1.3만 허용한다. 이 클래스는 프로세스 전역 기본값을 고정하지 않으므로 다른 코드가 기본 factory나 verifier를 바꾸면 플랫폼 기본 trust·hostname 검증을 보장하지 못한다. 실제 연동 전 전역 변경 여부를 확인해야 한다. 이 클래스 자체에는 별도의 trust 우회 또는 테스트 TLS 주입 경로가 없다. Android minSdk 26 기기에서 TLS 1.3이 제공되지 않으면 연결이 실패하며 낮은 TLS 버전으로 대체하지 않는다. 신뢰할 서버 origin의 provisioning과 인증된 API 호출 정책은 실제 연동 전에 확정해야 한다.

호출은 애플리케이션 계층에서 한 번만 시도하고 자동 redirect를 따르지 않는다. 네트워크 계층 전체에서 재전송이 절대 발생하지 않는다는 보장은 없으므로 서버의 idempotency 계약이 필요하다. mutation을 전송한 뒤 timeout·연결 실패가 발생하면 서버의 처리 여부는 **미확정**이며, caller는 성공이나 실패를 추정하거나 새 키로 자동 재전송해서는 안 된다. 상태조회·재시도 및 확인 UI는 서버 계약에 따라 별도로 구현해야 한다.

연결 5초·소켓 읽기 8초 제한과 10초 경과 검사를 사용한다. 본문이 천천히 계속 도착해도 검사 지점에서 경과를 확인하지만 연결·TLS·헤더·쓰기나 이미 차단된 read가 돌아오기 전에는 검사하지 못하므로 엄격한 전체 요청 시간 제한은 아니다. 백그라운드 스레드에서 호출하고 Activity 종료·프로세스 종료 뒤의 요청 결과를 무조건 취소한다고 가정하지 않는다.

호스트 시험은 작업 폴더에 임시 인증서를 생성해 신뢰·호스트명·TLS 1.2 거부·redirect 미추적·요청 헤더·중복 응답 헤더·크기·느린 본문을 확인한다. JVM 시험 과정의 임시 trust store는 테스트 Java 프로세스에만 지정하며 기기나 시스템 신뢰를 바꾸지 않는다. Android 실기기 및 운영 API 통합은 별도 시험이 필요하다.

```sh
export JAVA_HOME="$WORKSPACE/.local/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"
"$WORKSPACE/.local/node/bin/node" --test app/tests/api-transport/transport.test.mjs
```

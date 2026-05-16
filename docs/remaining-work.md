# 남아 있는 작업 목록

이 문서는 현재 `tmti_server` 아키텍처 분석 결과를 바탕으로, 후속 개발이 필요한 작업 중 사용자가 지정한 2~11번 항목만 정리한 문서입니다.

## 2. `ScheduleService` 책임 분리 ✅ 완료

상태: 완료. `ScheduleAccessPolicy`와 `ScheduleResponseMapper`를 추가해 권한/활성 리소스 검증 및 응답 매핑 책임을 `ScheduleService` 밖으로 분리했다.

완료된 분리 범위는 다음과 같다.

- `ScheduleAccessPolicy`: 방장 여부, 방 멤버 여부, 활성 방/일정 조회, 확정 일정 여부 검증
- `ScheduleResponseMapper`: Entity/Draft를 API 응답으로 변환

아직 `ScheduleService`에는 일정 생성 조율, 일정 저장, 조회, 편집 흐름이 남아 있다. 이후 기능이 더 커지면 다음 단계로 분리할 수 있다.

- `ScheduleGenerationService`: 일정 생성 유스케이스 조율
- `ScheduleCommandService`: 일정 추가, 재정렬, 확정, 재생성 등 변경 작업
- `ScheduleQueryService`: 일정 조회와 공유 일정 조회

## 3. 일정 생성 트랜잭션 경계 개선

현재 일정 생성 흐름은 `@Transactional` 메서드 안에서 consensus 계산과 LLM refinement 가능성이 있는 작업을 수행한다. 외부 API 또는 장시간 계산이 DB 트랜잭션과 같은 경계에 묶이면 커넥션 점유 시간이 길어지고 장애 전파 범위가 커질 수 있다.

개선 방향은 다음과 같다.

- 방/멤버/장소 데이터 조회
- 트랜잭션 밖에서 일정 후보 계산 및 LLM 보정
- 짧은 트랜잭션 안에서 `Schedule`, `ScheduleSlot`, `SatisfactionScore` 저장
- 외부 API 실패 시 deterministic fallback과 저장 실패를 명확히 분리

이 작업은 `ScheduleService` 책임 분리와 함께 진행하는 것이 좋다.

## 4. 현재 사용자 조회 중복 제거

여러 Controller에서 `SecurityContextHolder`를 직접 읽고, `CustomUserDetailsService.loadUserEntity()`로 현재 사용자를 조회하는 코드가 반복된다. 이 중복은 인증 처리 방식 변경 시 수정 지점을 늘리고, Controller의 본래 책임을 흐린다.

개선 후보는 다음과 같다.

- `@AuthenticationPrincipal` 기반 사용자 주입
- 커스텀 `CurrentUserArgumentResolver` 도입
- `CurrentUserProvider` 같은 작은 컴포넌트로 조회 로직 집중

목표는 Controller가 인증 컨텍스트를 직접 다루지 않고, 이미 검증된 현재 사용자 또는 사용자 ID만 받도록 만드는 것이다.

## 5. MVP 제약 해제

현재 일정 생성은 MVP 조건으로 제한되어 있다.

- 목적지: `충남`만 허용
- 시간: `09:00~21:00`만 허용
- 멤버 수: 제한된 범위만 허용

해당 제약은 `ConsensusService`의 일정 생성 경로에 직접 들어가 있다. 서비스가 확장되면 다음 작업이 필요하다.

- 목적지별 장소 후보 필터링 일반화
- 임의 시간 범위에 맞는 slot template 생성
- 여행 일수 또는 날짜 범위 기반 일정 생성 지원
- 지역별 TourAPI sync/enrich 전략 분리
- 제약 위반 메시지와 API 계약 정리

이 작업은 단순 조건 삭제가 아니라 일정 생성 알고리즘의 입력 모델 확장으로 접근해야 한다.

## 6. 장소 후보 검색 최적화

현재 일부 일정 생성/검색 로직은 `findByDelYn()`으로 활성 장소를 전체 조회한 뒤 애플리케이션 메모리에서 필터링한다. 장소 데이터가 작을 때는 문제가 적지만, TourAPI 데이터가 늘어나면 성능 병목이 될 수 있다.

개선 방향은 다음과 같다.

- 장소 검색 조건을 DB query로 pushdown
- 이름, 주소, 카테고리 기반 검색 인덱스 검토
- 지역, 좌표, 카테고리, 운영시간 조건을 QueryDSL로 조합
- 일정 생성 후보군에 limit/ranking 적용
- 사용 중인 장소 제외 조건도 DB 레벨에서 처리

이 작업은 데이터량 증가 전에 선제적으로 설계하는 것이 좋다.

## 7. TourAPI 동기화 운영화

TourAPI 연동은 장소 sync/enrich 기능을 제공하지만, 운영 관점에서는 더 명확한 실행 전략과 관측성이 필요하다.

필요 작업은 다음과 같다.

- 정기 동기화 스케줄 정책 확정
- 실패 재시도와 부분 실패 처리
- API 호출량 제한 또는 rate limit 대응
- sync/enrich 결과 로깅과 메트릭화
- 중복 장소 병합 정책 검증
- 오래된 장소 정보 갱신 기준 정의
- 관리자 전용 실행 권한과 감사 로그 검토

TourAPI는 장소 후보 품질에 직접 영향을 주므로, 일정 생성 품질 개선과 연결해서 관리해야 한다.

## 8. OpenAI fallback 및 관측성 강화

현재 LLM refinement는 deterministic 일정 생성 결과를 보정하는 선택적 단계로 보인다. 외부 LLM 호출은 실패, 지연, 응답 파싱 실패가 발생할 수 있으므로 fallback과 관측성을 명확히 해야 한다.

개선 방향은 다음과 같다.

- LLM 호출 성공률 기록
- latency 기록
- fallback 사용 여부 저장 또는 로깅
- 응답 파싱 실패 원인 분류
- option type별 LLM 효과 측정
- 일정 생성 결과에 사용 provider와 fallback 여부 일관 반영
- 장애 시 사용자에게 노출할 메시지와 내부 로그 분리

목표는 LLM이 실패해도 일정 생성은 안정적으로 완료되고, 실패 원인은 운영자가 추적할 수 있게 하는 것이다.

## 9. 운영 JWT secret 강제

현재 설정에는 개발용 JWT secret 기본값이 존재한다. 개발 환경에서는 편리하지만, 운영 환경에서 환경변수 누락 시 약한 기본값으로 서비스가 뜰 위험이 있다.

개선 방향은 다음과 같다.

- 운영 profile에서는 `JWT_SECRET` 미설정 시 애플리케이션 부팅 실패
- secret 길이와 entropy 검증
- 개발/local/test profile과 운영 profile 설정 분리
- 배포 문서에 필수 환경변수 명시

이 작업은 보안상 우선순위가 높다.

## 10. 쿠키 secure 설정 분리

현재 JWT session cookie 생성 시 `secure(false)`로 설정되어 있다. 로컬 개발에서는 필요할 수 있지만, HTTPS 운영 환경에서는 secure cookie를 사용해야 한다.

개선 방향은 다음과 같다.

- profile 또는 설정값으로 cookie secure 여부 분리
- 운영 환경 기본값은 `secure(true)`
- SameSite 정책 검토
- OAuth redirect/callback 흐름에서 cookie 전달이 정상 동작하는지 검증
- 프론트엔드 도메인과 API 도메인이 다를 때의 쿠키 정책 확인

인증 안정성과 보안을 동시에 고려해야 하는 작업이다.

## 11. CORS 및 프론트 URL 운영 설정 검증

현재 프론트엔드 URL, API base URL, OAuth callback base URL이 환경변수 기반으로 설정된다. 로컬에서는 동작하더라도 운영 배포에서는 도메인, HTTPS, 프록시 경로, 쿠키 정책에 따라 문제가 생길 수 있다.

검증할 항목은 다음과 같다.

- `FRONTEND_BASE_URL`
- `API_BASE_URL`
- `OAUTH_CALLBACK_BASE_URL`
- Google OAuth redirect URI
- Kakao OAuth redirect URI
- CORS allowed origin
- 쿠키 path/domain/samesite/secure 조합
- `/api` context path와 프록시 라우팅 일치 여부

이 작업은 배포 전 체크리스트로 관리하는 것이 좋다.

## 권장 실행 순서

1. `ScheduleService` 책임 분리
2. 일정 생성 트랜잭션 경계 개선
3. 현재 사용자 조회 중복 제거
4. 운영 JWT secret 강제
5. 쿠키 secure 설정 분리
6. CORS/OAuth 운영 설정 검증
7. 장소 후보 검색 최적화
8. TourAPI 동기화 운영화
9. OpenAI fallback 및 관측성 강화
10. MVP 제약 해제

# TripSync Server 남은 구현사항 정리

> 작성일: 2026-04-04  
> 기준: `docs/PRD.md`, `docs/API_SPEC.md`, `docs/TECH_SPEC.md`, `docs/TEST_PLAN.md` 와 `src/` 실제 구현 비교  
> 범위: **서버에서 추가 구현이 필요하거나, 서버 응답/운영 기능이 아직 부족한 항목만** 정리

---

## 1. 현재 서버 구현 상태 요약

현재 `tmti_server`에는 아래 MVP 핵심 흐름이 이미 구현되어 있다.

- 인증: Google OAuth, local 회원가입/로그인, guest 세션 (`src/auth/*`)
- TPTI 문항/제출/결과 조회/공개 공유 (`src/tpti/*`)
- 방 생성/공유코드 조회/참여/멤버 조회 (`src/room/*`)
- 갈등 지도 계산 (`src/conflict/*`, `src/consensus/consensus.service.ts`)
- 일정 3옵션 생성/확정/단건 조회/재생성/공개 공유 (`src/schedule/*`)
- TourAPI 수집/상세 보강 수동 실행 (`src/tour-api/*`, `src/place/*`)
- LLM 기반 일정 refinement + deterministic fallback (`src/llm/*`, `src/consensus/*`)

즉, **공모전 데모용 핵심 P0 골격은 동작하는 상태**다.  
다만 PRD/TECH_SPEC 기준으로 보면, 아래 항목들은 아직 미구현 또는 부분 구현 상태다.

---

## 2. 우선순위별 남은 구현사항

## [TPTI 제출 이후 방 스냅샷/ready 상태 자동화]

### 목적
문서에 정의된 사용자 플로우대로, `TPTI 제출 완료 → 방 스냅샷 반영 → room ready 전환`이 한 흐름에서 일어나도록 맞춘다.

### 현재 상태
- `POST /api/tpti/submit` 은 현재 `tpti_results`만 생성하고 종료됨 (`src/tpti/tpti.service.ts`)
- 방 스냅샷 생성과 `ready` 상태 전환은 `POST /api/rooms/:shareCode/join` 에서 `tptiResultId`를 함께 보낼 때만 일어남 (`src/room/room.service.ts`)
- 하지만 문서는 TPTI 제출 후 자동으로 방 상태가 반영되는 흐름을 전제함 (`docs/API_SPEC.md`, `docs/TECH_SPEC.md`)

### API 엔드포인트
- 기존 연계: `POST /api/tpti/submit`
- 기존 연계: `POST /api/rooms/:shareCode/join`

### 요청/응답 핵심 필드
- `tptiResultId`
- `roomId` 또는 `shareCode`
- `roomStatus`
- 방별 스냅샷 생성 여부

### 완료 기준
- [ ] TPTI 제출 직후 방 참여 플로우와의 연계 기준이 명확히 정리됨
- [ ] 방 참여 중인 사용자는 결과 제출만으로 스냅샷/ready 상태가 반영되거나, 그렇지 않다면 API 계약이 문서와 동일하게 수정됨
- [ ] 관련 통합 테스트 추가

### 우선순위: P0
### 의존성: 방 참여 UX, 프론트 제출 플로우

---

## [숨은 명소 배지/공유 응답 보강]

### 목적
PRD의 P0 요구사항인 "숨은 명소 배지"를 실제 화면에서 안정적으로 표시할 수 있도록 일정 응답에 배지 판별용 데이터를 포함한다.

### 현재 상태
- 합의 엔진은 숨은 명소 여부를 내부 가중치 계산에 사용함 (`src/consensus/consensus.service.ts:765` 부근)
- 일정 상세/공개 공유 응답에는 이미 `destination`, `tripDate`, 좌표(`latitude`, `longitude`), `isDepopulationArea` 가 포함돼 있음 (`src/schedule/schedule.service.ts`, `test/schedule.service.spec.ts`)
- 다만 프론트가 바로 "숨은 명소" 배지를 붙이기 위한 **명시적 필드명**(`hiddenGem`, `populationDeclineArea`)은 아직 없음
- 즉, 이 항목의 본질은 "공유 응답 기본 필드 추가"가 아니라 **배지 판별 의미를 API 계약에 명확히 노출하는 것**에 가까움

### API 엔드포인트
- 기존 확장: `GET /api/schedules/:id`
- 기존 확장: `GET /api/share/schedules/:scheduleId`

### 요청/응답 핵심 필드
- `slots[].place.hiddenGem`
- `slots[].place.populationDeclineArea`
- `slots[].place.latitude`, `slots[].place.longitude` (이미 제공 중, 유지 대상)
- `slots[].place.isDepopulationArea` 와 신규 배지 필드 간 매핑 규칙

### 완료 기준
- [ ] 일정 상세 응답에서 숨은 명소 배지 표시 가능한 필드 제공
- [ ] 공개 공유 응답에서도 배지 표시 가능한 최소 필드 포함
- [ ] `isDepopulationArea` 와 `hiddenGem`/`populationDeclineArea` 의미 차이를 문서로 고정
- [ ] 관련 응답 스펙/API 테스트 갱신

### 우선순위: P0
### 의존성: `place.metadataTags` 파싱 규칙 유지

---

## [TourAPI 자동 수집/보강 배치]

### 목적
현재 수동 호출 기반인 TourAPI 동기화를 배치화해서 데모/운영 환경에서 데이터 최신성을 안정적으로 유지한다.

### 현재 상태
- 수집/보강 API는 구현되어 있음 (`src/tour-api/tour-api.controller.ts`)
- 하지만 스케줄러/크론 작업은 없음
- `package.json`에 `@nestjs/schedule` 의존성이 없고, `src/`에도 `@Cron` 사용이 없음
- TECH_SPEC은 일배치/보관 정책을 전제함 (`docs/TECH_SPEC.md:431`, `docs/TECH_SPEC.md:866`)

### API 엔드포인트
- 내부 배치 작업 (신규)
- 필요 시 운영용 수동 트리거는 기존 `POST /api/tour-api/sync/chungnam`, `POST /api/tour-api/enrich/chungnam` 유지

### 요청/응답 핵심 필드
- 마지막 성공 시각
- 처리 건수(`fetched`, `synced`, `enriched`, `failed`)
- 실패 사유 요약

### 완료 기준
- [ ] 충남 TourAPI 수집 배치 주기 정의 및 구현
- [ ] 상세 보강 배치 주기 정의 및 구현
- [ ] 실패 재시도/부분 실패 로그 남김
- [ ] 운영자가 최근 실행 결과를 확인할 수 있는 최소 상태 저장 또는 로그 확보

### 우선순위: P0
### 의존성: TourAPI 서비스키, 배치 실행 환경

---

## [운영 가드레일: rate limit / 요청 로깅 / 설정 검증]

### 목적
실서비스/데모 환경에서 인증, 외부 API 호출, 일정 생성 API를 안전하게 보호한다.

### 현재 상태
- 전역 ValidationPipe, ExceptionFilter는 있음 (`src/main.ts`)
- requestId 생성은 있으나 요청 단위 로깅/추적은 없음 (`src/common/dto/api-response.dto.ts`, `src/common/errors/api-exception.filter.ts`)
- TECH_SPEC에는 rate limit과 운영 로그가 명시돼 있음 (`docs/TECH_SPEC.md:859`, `docs/TECH_SPEC.md:871`)
- `package.json`에 throttling/logger 관련 구현 흔적이 없음
- 공유코드는 `Math.random()` 기반 단순 생성이라 추측/충돌 대응이 약함 (`src/room/room.service.ts`)
- `JWT_SECRET` 미설정 시 하드코딩된 dev secret으로 서명됨 (`src/auth/session-token.util.ts`)

### API 엔드포인트
- 공통 미들웨어/가드/인터셉터 작업

### 요청/응답 핵심 필드
- 요청별 `requestId`
- 사용자/방/일정/외부 API 호출 로그
- OAuth 시작/콜백, 일정 생성, TourAPI 동기화 rate limit 정책

### 완료 기준
- [ ] OAuth 시작/로그인/게스트 세션/API 생성 계열 rate limit 적용
- [ ] 일정 생성/TourAPI 연동에 requestId 기반 로그 추가
- [ ] 필수 env 누락 시 부팅 단계에서 명확히 실패하거나 경고하도록 정리
- [ ] 공유코드 충돌 재시도 및 추측 저항성 강화
- [ ] staging/demo 환경에서 dev JWT secret fallback 제거 또는 명시적 차단
- [ ] 운영 문서에 제한값과 장애 대응 절차 반영

### 우선순위: P0
### 의존성: 배포 환경 로그 수집 방식 결정

---

## [일정 생성 가드레일: 락/재시도/만족도 기준 보강]

### 목적
일정 생성 API를 동시성, LLM 실패, 저품질 일정 상황에서도 문서 수준에 맞게 안전하게 만든다.

### 현재 상태
- 일정 생성 시 room-level lock이 없음 (`src/schedule/schedule.service.ts`)
- LLM refinement는 1회 시도 후 fallback하는 구조이며, 문서/테스트 계획의 재시도 기대치보다 단순함 (`src/consensus/consensus.service.ts`)
- 낮은 만족도에 대해 \"최대 3회 재조정\" 대신 최소 기준으로 점수를 보정하는 형태에 가까움 (`src/consensus/consensus.service.ts`)

### API 엔드포인트
- 기존 확장: `POST /api/rooms/:id/generate-schedule`
- 기존 확장: `POST /api/schedules/:id/regenerate`

### 요청/응답 핵심 필드
- 생성 요청 idempotency/lock 상태
- LLM retry 횟수
- fallback 여부
- 재조정 횟수

### 완료 기준
- [ ] 같은 방에 대한 동시 일정 생성 방지 장치 구현
- [ ] LLM invalid/timeout 시 재시도 정책 명확화
- [ ] 만족도 기준 미달 시 재조정 또는 실패 처리 규칙 구현
- [ ] 테스트 계획의 관련 케이스와 실제 동작 일치

### 우선순위: P0
### 의존성: LLM 비용/지연 허용치 결정

---

## [이동 거리/동선 제약의 실제 반영]

### 목적
문서에서 약속한 \"30분 이내 이동\"과 동선 일관성을 실제 일정 선택 로직에 반영한다.

### 현재 상태
- 장소 DB에는 위경도가 있으나 스케줄링 입력으로 충분히 활용되지 않음 (`src/schedule/schedule.service.ts`, `src/place/place.service.ts`)
- 현재 장소 랭킹은 취향 점수/카테고리/운영시간 중심이며, 슬롯 간 이동 거리 제약은 사실상 빠져 있음 (`src/consensus/consensus.service.ts`)
- PRD/TECH_SPEC은 거리/이동 제약을 명시함 (`docs/PRD.md`, `docs/TECH_SPEC.md`)

### API 엔드포인트
- 내부 합의 엔진 로직
- 필요 시 `GET /api/schedules/:id/route` 와 연계

### 요청/응답 핵심 필드
- 장소 간 거리/이동시간
- 이동 제약 통과 여부
- 추천 제외 사유

### 완료 기준
- [ ] 슬롯 간 거리/이동시간이 실제 후보 선택에 반영됨
- [ ] 30분 초과 동선 배제 또는 강한 패널티 적용
- [ ] 관련 테스트 케이스 추가

### 우선순위: P0
### 의존성: 거리 계산 방식(직선거리/외부 라우팅 API) 결정

---

## [갈등 지도 단계의 그룹 만족도 예측]

### 목적
PRD F2-7에 맞춰 일정 생성 전에 현재 멤버 구성이 어느 정도로 맞는 팀인지 빠르게 보여준다.

### 현재 상태
- 갈등 지도 API는 공통 축/충돌 축/요약만 제공 (`src/conflict/conflict.service.ts`)
- PRD에는 `그룹 만족도 예측`이 P1로 명시돼 있음 (`docs/PRD.md:205`)
- 현재 만족도는 일정 생성 이후 옵션별로만 계산됨 (`src/schedule/schedule.service.ts`)

### API 엔드포인트
- 기존 확장: `GET /api/rooms/:id/conflict-map`

### 요청/응답 핵심 필드
- `predictedGroupSatisfaction`
- `predictedLowestMemberSatisfaction`
- `predictionBasis` (예: 평균 편차, 상위 충돌 축 등)

### 완료 기준
- [ ] 갈등 지도 응답에 예측 만족도 포함
- [ ] 계산식이 문서화되고 테스트로 고정됨
- [ ] 일정 생성 후 실제 만족도와 비교 가능한 최소 지표 정의

### 우선순위: P1
### 의존성: `ConsensusService` 점수 로직 재사용 또는 별도 추정식 정의

---

## [신규 멤버 참여 후 stale 상태 표기]

### 목적
기존 일정은 유지하되, 새 멤버 참여로 인해 재생성이 필요한 상태를 서버가 명확히 전달한다.

### 현재 상태
- 문서는 \"기존 일정 유지 + 재생성 필요 상태 표시\"를 전제함 (`docs/TECH_SPEC.md`)
- 하지만 현재 방 상태 enum은 `waiting|ready|completed` 뿐이고 (`src/common/enums/domain.enums.ts`)
- `refreshRoomStatus()` 는 프로필 수만 기준으로 상태를 덮어씀 (`src/room/room.service.ts`)

### API 엔드포인트
- 기존 확장: `GET /api/rooms/:id`
- 기존 확장: `GET /api/rooms/:id/members`

### 요청/응답 핵심 필드
- `status`
- `regenerationRequired`
- `staleSinceMemberJoin`

### 완료 기준
- [ ] 신규 멤버 참여 후 기존 일정 stale 여부를 서버가 내려줌
- [ ] 프론트가 재생성 CTA를 띄울 수 있는 상태값 제공
- [ ] 일정이 있는 방과 없는 방의 상태 전이가 구분됨

### 우선순위: P1
### 의존성: 방 상태 모델 확장 여부 결정

---

## [기여 카드 API]

### 목적
PRD F3-8에 맞춰 "이 여행이 어떤 지역 방문에 기여했는가"를 설명 가능한 카드 데이터로 제공한다.

### 현재 상태
- 지역 발굴형 일정과 숨은 명소 선택 로직은 존재함 (`src/consensus/consensus.service.ts`)
- 하지만 일정 확정 이후 기여 카드용 응답/엔드포인트는 없음
- `src/schedule/*`에도 contribution 관련 필드가 없다

### API 엔드포인트
- 신규 제안: `GET /api/schedules/:id/contribution-card`
- 또는 기존 `GET /api/schedules/:id` 응답 확장

### 요청/응답 핵심 필드
- `regionName`
- `hiddenGemPlaces[]`
- `contributionMessage`
- `evidence` (예: 인구감소지역 태그, 일정 내 포함 슬롯 수)

### 완료 기준
- [ ] 일정 확정 후 호출 가능한 기여 카드 응답 제공
- [ ] 지역 발굴형/균형형/개성형 모두 동일 포맷으로 계산 가능
- [ ] 공유/프론트 카드 렌더링에 필요한 최소 데이터 제공

### 우선순위: P1
### 의존성: 숨은 명소/인구감소지역 메타데이터 응답 노출

---

## [지도 뷰 지원용 일정 경로 데이터]

### 목적
PRD F3-9에 맞춰 프론트가 일정 장소를 지도 위에 표시할 수 있도록 좌표/경로 데이터를 제공한다.

### 현재 상태
- 장소 DB에는 위경도가 있음 (`prisma/schema.prisma`, `src/place/place.service.ts`)
- 하지만 일정 조회 응답에는 위경도/경로 정보가 없음 (`src/schedule/schedule.service.ts:263`)
- TECH_SPEC은 Phase 2에서 실제 라우팅 API 분리를 언급함 (`docs/TECH_SPEC.md:565`)

### API 엔드포인트
- 기존 확장: `GET /api/schedules/:id`
- 신규 제안: `GET /api/schedules/:id/route`

### 요청/응답 핵심 필드
- `slots[].place.latitude`
- `slots[].place.longitude`
- `routePolyline` 또는 구간별 `travelSegments[]`
- 이동시간 추정치(초기에는 직선거리/단순 근사도 가능)

### 완료 기준
- [ ] 일정 응답 또는 별도 route API에서 지도 렌더링 가능한 좌표 제공
- [ ] 최소한 슬롯 순서 기준 마커 표시가 가능함
- [ ] 향후 실제 라우팅 API 연동 가능한 인터페이스 정의

### 우선순위: P1
### 의존성: 장소 좌표 응답 노출, 프론트 지도 요구사항 확정

---

## [일정 버전 목록 / 최신 확정 일정 조회]

### 목적
재생성된 일정의 이력을 조회하고, 방 기준으로 최신/확정 일정을 쉽게 불러오게 한다.

### 현재 상태
- 서버는 `version`, `optionType`, `isConfirmed`를 저장함 (`prisma/schema.prisma`, `src/schedule/schedule.service.ts`)
- 하지만 조회 API는 `GET /api/schedules/:id` 단건만 존재
- `docs/API_SPEC.md:984` 에도 "이전 버전 목록 API 추가 여부"가 미결로 남아 있음

### API 엔드포인트
- 신규 제안: `GET /api/rooms/:id/schedules`
- 신규 제안: `GET /api/rooms/:id/schedules/latest`

### 요청/응답 핵심 필드
- `version`
- `optionType`
- `isConfirmed`
- `groupSatisfaction`
- `createdAt`

### 완료 기준
- [ ] 방 기준 일정 버전 목록 조회 가능
- [ ] 최신 생성 버전과 확정 버전을 각각 식별 가능
- [ ] 공개 공유/재진입 플로우에서 직접 scheduleId를 몰라도 최신 일정 접근 가능

### 우선순위: P1
### 의존성: 프론트 일정 진입 플로우 결정

---

## [데이터 보존/정리 배치]

### 목적
문서에 정의된 soft delete + 장기 purge 정책을 실제 운영 기능으로 마무리한다.

### 현재 상태
- soft delete 유틸은 존재 (`src/common/soft-delete/*`)
- 하지만 삭제/정리 배치 구현은 없음
- TECH_SPEC/DB_SCHEMA는 6개월 보관 후 purge 가능이라고 정의 (`docs/TECH_SPEC.md:431`, `docs/DB_SCHEMA.md`)

### API 엔드포인트
- 내부 배치 작업 (신규)

### 요청/응답 핵심 필드
- 정리 대상 테이블
- 삭제 건수
- 보관 기준 시각

### 완료 기준
- [ ] soft-deleted 데이터 purge 배치 구현
- [ ] 배치 dry-run 또는 운영 보호 장치 제공
- [ ] 문서에 실행 주기와 복구 불가 정책 명시

### 우선순위: P2
### 의존성: 운영 정책 확정

---

## [테스트 계획 대비 구현 검증 갭 축소]

### 목적
문서에 적어둔 테스트 범위와 실제 자동화 검증 수준의 차이를 줄여 배포/데모 리스크를 낮춘다.

### 현재 상태
- 현재 자동화 테스트는 8개 spec 파일 중심 (`test/`)
- `room`, `conflict`, `tpti` 전용 테스트가 얇고, 성능/E2E/운영성 테스트는 없음
- `docs/TEST_PLAN.md` 의 범위가 실제 자동화 수준보다 넓다

### API 엔드포인트
- 테스트 작업 전반

### 요청/응답 핵심 필드
- 에러 코드 회귀
- 방 상태 전이
- 일정 생성 동시성
- TourAPI/LLM 실패 시 fallback

### 완료 기준
- [ ] `room`, `conflict`, `tpti` 핵심 경로 자동화 보강
- [ ] schedule/tour-api 에러 코드 회귀 테스트 추가
- [ ] 최소 1개 이상 데모 플로우 E2E 시나리오 확보

### 우선순위: P2
### 의존성: 테스트 환경/fixture 정리

---

## [TPTI 수동 조정 UX용 서버 보강]

### 목적
현재 기능 플래그 수준인 manual adjustment를 사용자 플로우 기준으로 완성한다.

### 현재 상태
- `POST /api/tpti/submit` 는 `manualAdjustments`를 받을 수 있음 (`src/tpti/tpti.service.ts`, `src/tpti/dto/submit-tpti.dto.ts`)
- 다만 별도 수정 API, 수정 이력, 재계산 기준은 없음
- PRD는 사용자가 결과를 보고 수치를 미세 조정하는 경험을 전제함 (`docs/PRD.md:130`)

### API 엔드포인트
- 기존 유지 또는 신규 제안: `PATCH /api/tpti/result/:resultId`

### 요청/응답 핵심 필드
- `manualAdjustments`
- `isManuallyAdjusted`
- 수정 전/후 점수

### 완료 기준
- [ ] 수동 조정의 서버 기준 플로우 확정
- [ ] 기능 플래그 ON/OFF 시 동작이 테스트로 보장됨
- [ ] 조정 결과가 방 스냅샷/갈등 지도/일정 생성에 일관되게 반영됨

### 우선순위: P2
### 의존성: 프론트 결과 카드 UX 확정

---

## 3. 추천 구현 순서

1. **일정 생성 가드레일(락/재시도/만족도 기준)**
2. **운영 가드레일(rate limit / 로그 / env 검증)**
3. **TourAPI 자동 수집/보강 배치**
4. **숨은 명소 배지/공유 응답 보강(명시 필드 중심)**
5. **TPTI 제출 이후 방 스냅샷/ready 상태 자동화**
6. **이동 거리/동선 제약의 실제 반영**
7. **갈등 지도 단계의 그룹 만족도 예측**
8. **일정 버전 목록 / 최신 확정 일정 조회**
9. **신규 멤버 참여 후 stale 상태 표기**
10. **기여 카드 API**
11. **지도 뷰 지원용 경로 데이터**
12. **테스트 계획 대비 구현 검증 갭 축소**
13. **데이터 보존/정리 배치**
14. **TPTI 수동 조정 UX용 서버 보강**

---

## 4. 메모

- 위 목록은 **프론트 작업이 아니라 서버 측 추가 책임**이 필요한 항목만 추렸다.
- 공모전 데모 관점에서는 **1~6번**이 우선이다.
- 사용자 경험 확장 관점에서는 **7~11번**이 다음 단계다.
- 운영/품질 마감 작업은 **12~14번**이다.
- 현재 프론트 기준으로 "추천 단계에서 시간 정보를 전면 노출하지 않는다"는 조건이 있으므로, 이동 거리/동선 제약은 **응답 필드 추가보다 내부 품질 제약**으로 우선 해석한다.

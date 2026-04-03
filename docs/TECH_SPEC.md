# TripSync MVP 기술명세서

> 기준 문서: `docs/PRD.md` v1.1  
> 작성일: 2026-04-02  
> 상태: Draft  
> 목적: PRD를 실제 구현 가능한 수준의 기술 설계로 구체화한다.

---

## 1. 문서 목적 및 범위

### 1.1 목적

본 문서는 TripSync MVP의 기능 요구사항을 프론트엔드, 백엔드, 데이터, 인프라, 알고리즘 수준으로 구체화한 기술 명세서다.  
핵심 목표는 **"그룹 여행 취향 충돌을 정량화하고, 시간 배분 기반 합의 일정으로 변환하는 서비스"**를 빠르게 구현 가능한 형태로 정의하는 것이다.

### 1.2 MVP 범위

- 1일 여행 일정 생성 (09:00~21:00)
- 충남권 한정
- 그룹 인원 2~5명 기준
- 3개 핵심 화면
  1. TPTI 검사
  2. 그룹 갈등 지도
  3. AI 합의 일정
- 방장 OAuth 로그인(Kakao/Google)
- 동행자 비회원 참여
- 방장 수동 생성 트리거 방식
- TourAPI 기반 장소 캐시 + 메타 태깅
- LLM 기반 일정 구조 생성

### 1.3 MVP 제외 범위

- 2박 3일 이상 멀티데이 일정
- 실시간 교통/날씨 반영
- 결제/예약 직접 연동
- 다국어 지원
- 관리자 콘솔
- 외부 리뷰 데이터 결합

### 1.4 설계 원칙

1. **합의 우선:** 개인 추천이 아니라 그룹 내 납득 가능한 일정 생성이 목적이다.
2. **설명 가능성:** 갈등 축, 슬롯 배분 이유, 만족도 계산 근거가 노출되어야 한다.
3. **Guest-first:** 동행자는 로그인 없이 최소 단계로 참여해야 한다.
4. **환각 차단:** LLM은 일정 구조만 만들고, 장소 선택은 캐시된 후보 목록 안에서만 허용한다.
5. **모바일 퍼스트:** 카카오톡 인앱 브라우저 기준으로 UI/흐름을 설계한다.
6. **MVP 우선:** 자동화보다 단순하고 검증 가능한 구조를 우선한다.

---

## 2. 시스템 개요

### 2.1 핵심 사용자 유형

| 사용자 유형 | 설명 | 권한 |
|---|---|---|
| 방장(Host) | 여행 방 생성 및 일정 생성 주체 | 회원가입, 방 생성, 일정 생성/재생성 |
| 동행자(Guest/Member) | 공유 링크로 참여하는 사용자 | 비회원 세션 생성, TPTI 검사, 결과 조회 |

### 2.2 핵심 도메인 용어

| 용어 | 기술적 의미 |
|---|---|
| TPTI 결과 | 4개 축 점수(0~100)와 캐릭터 별명을 가진 사용자 취향 프로필 |
| 갈등 지도 | 방 참여자들의 TPTI 집계 결과와 공통 지대/충돌 축 분석 결과 |
| 합의 엔진 | 갈등 축 강도 기반 시간 슬롯 배분 + 장소 후보 매칭 + 만족도 계산 로직 |
| 슬롯 | 일정 내 최소 1시간~최대 3시간 단위의 배치 블록 |
| 공통 슬롯 | 모두의 공통 지대를 반영하는 일정 블록 |
| 개인 슬롯 | 특정 사용자/사용자군 취향을 우선 반영하는 일정 블록 |

### 2.3 TPTI 축 점수 정의

PRD에는 축 방향이 암묵적으로만 존재하므로, 구현 시 아래 기준으로 고정한다.

| 필드 | 0점 | 100점 |
|---|---|---|
| `mobility_score` | Stay(휴식형) | Walker(활동형) |
| `photo_score` | Eyes(실속형) | Artist(사진형) |
| `budget_score` | Cost-effective(가성비형) | Luxury(고예산형) |
| `theme_score` | Nature(자연형) | City(도심형) |

---

## 3. 상위 아키텍처

### 3.1 논리 아키텍처

```text
[모바일 웹 사용자]
        ↓
[Next.js 프론트엔드]
  - SSR/SEO/OG
  - 모바일 UI
  - API 프록시(선택)
        ↓
[NestJS API 서버]
  - Auth
  - TPTI
  - Room
  - Conflict
  - Consensus
  - Schedule
  - TourAPI Batch
        ↓
[MySQL]
  - 사용자/방/일정/장소 캐시
        ↘
         [LLM API]
        ↗
     [TourAPI]
```

### 3.2 물리 배포 구조

| 계층 | 배포 위치 | 역할 |
|---|---|---|
| Web | Vercel | Next.js SSR, OG 메타, 모바일 화면 제공 |
| API | AWS EC2 단일 인스턴스 | NestJS REST API, 배치 작업, LLM/TourAPI 연동 |
| DB | EC2 내부 MySQL 또는 동일 VPC MySQL | MVP 데이터 저장소 |

### 3.3 권장 런타임 분리

현재 저장소 구조를 기준으로 아래와 같이 역할을 분리한다.

```text
/tmti_web     -> Next.js 앱
/tmti_server  -> NestJS API 서버
/docs         -> PRD, 기술 명세, 운영 문서
```

---

## 4. 프론트엔드 기술 명세

### 4.1 기술 선택

- **Framework:** Next.js (App Router 권장)
- **UI:** Tailwind CSS
- **차트:** Radar Chart 라이브러리 1종 사용
- **통신:** REST API + JSON
- **렌더링 전략:**
  - 랜딩/공유 페이지: SSR/정적 최적화
  - 앱 화면: CSR 중심 + 필요한 메타 페이지는 SSR

### 4.2 페이지/라우트 설계

| 경로 | 화면 | 설명 | 접근 유형 |
|---|---|---|---|
| `/` | 랜딩 | 서비스 소개, 방장 CTA | 공개 |
| `/tpti` | TPTI 검사 | 8문항 검사, 진행률 표시 | 공개/세션 필요 |
| `/tpti/result` | 결과 카드 | 레이더 차트, 별명, 공유 버튼 | 세션 필요 |
| `/rooms/new` | 방 생성 | 방장 전용, 여행 방 생성 | 로그인 필요 |
| `/join/[shareCode]` | 초대 진입 | 공유 코드 검증, 게스트 세션 생성, 검사 유도 | 공개 |
| `/rooms/[roomId]/conflict` | 갈등 지도 | 멤버별 TPTI 비교, 충돌 요약 | 방 멤버만 |
| `/rooms/[roomId]/schedule` | 합의 일정 | 타임라인, 만족도, 재생성 | 방 멤버만 |
| `/share/tpti/[resultId]` | 공유 OG 페이지 | 결과 카드 미리보기 전용 | 공개 |
| `/share/schedule/[scheduleId]` | 일정 공유 페이지 | 읽기 전용 일정 공유 | 공개 |

### 4.3 주요 UI 컴포넌트

| 컴포넌트 | 역할 |
|---|---|
| `QuestionStepper` | 8문항 단계형 진행 UI |
| `ProgressBar` | 완료율 표시 |
| `TptiRadarChart` | 개인/그룹 레이더 차트 시각화 |
| `ScoreBars` | 축별 0~100 점수 표시 |
| `CharacterCard` | 별명, 요약 문구, 공유 CTA |
| `ConflictSummaryCard` | 충돌 축/공통 지대 자연어 요약 |
| `ScheduleTimeline` | 시간순 슬롯 렌더링 |
| `SatisfactionPanel` | 개인별/그룹 만족도 표시 |
| `RegenerateForm` | 날짜/지역/조건 재생성 입력 |

### 4.4 프론트 상태 흐름

1. 사용자 세션 확인
2. TPTI 검사 응답 저장
3. 검사 제출 후 결과 카드 렌더링
4. 방 참여 여부에 따라 자동 room join 처리
5. 갈등 지도 조회
6. 일정 생성 요청 → 로딩 → 결과 렌더링

### 4.5 프론트 검증 규칙

- 닉네임: 2~12자
- TPTI 문항: 8개 모두 응답 필수
- 일정 생성 입력: 지역, 여행 날짜 필수
- 방 접근: 유효한 공유 코드 또는 방 멤버 권한 필요

### 4.6 공유/OG 처리

- TPTI 결과 카드와 일정 요약은 공유 링크별 메타 태그를 생성한다.
- OG 이미지에는 아래 정보만 포함한다.
  - TPTI: 별명, 4축 요약, 대표 컬러
  - 일정: 지역, 날짜, 대표 슬롯 2~3개
- 개인정보 최소화를 위해 이메일/실명은 노출하지 않는다.

---

## 5. 백엔드 기술 명세

### 5.1 기술 선택

- **Framework:** NestJS
- **Language:** TypeScript
- **ORM:** TypeORM 또는 Prisma 중 1종 선택
- **DB:** MySQL 8.x
- **Batch:** NestJS Schedule(Cron)
- **Validation:** class-validator 기반 DTO 검증
- **Auth:** JWT 기반 인증 (방장/게스트 공용)

### 5.2 NestJS 모듈 구조

```text
src/
├── auth/
├── tpti/
├── room/
├── conflict/
├── consensus/
├── schedule/
├── place/
├── tour-api/
├── llm/
└── common/
```

### 5.3 모듈별 책임

| 모듈 | 책임 |
|---|---|
| `auth` | 방장 OAuth 로그인(Kakao/Google), 게스트 세션 발급, JWT 검증 |
| `tpti` | 문항 제공, 응답 저장, 점수 계산, 캐릭터 별명 생성 |
| `room` | 방 생성, 공유 코드 발급, 멤버 참여, 멤버 상태 관리 |
| `conflict` | 공통 지대/충돌 축 계산, 갈등 지도 생성, 요약 메시지 생성 |
| `consensus` | 슬롯 배분, 장소 후보 조회, LLM 요청/응답 검증, 만족도 계산 |
| `schedule` | 일정 버전 저장, 일정 조회, 재생성 이력 관리 |
| `place` | 장소 조회, 필터링, 거리/운영시간 검증 |
| `tour-api` | TourAPI 일배치 수집, 정규화, 캐시 upsert, 메타 태깅 작업 큐 |
| `llm` | LLM 프롬프트 조합, JSON 응답 파싱, 모델 추상화 |
| `common` | 예외 처리, 로깅, 응답 포맷, 설정 관리 |

### 5.4 인증 전략

#### 방장
- 회원가입/로그인은 **Kakao OAuth 또는 Google OAuth만 지원**
- 최초 OAuth 로그인 시 사용자 레코드를 자동 생성한다
- 재로그인 시 `provider + provider_user_id` 기준으로 기존 계정을 식별한다
- OAuth 성공 후 서비스용 JWT를 발급한다
- MVP에서는 Kakao/Google 간 계정 병합(account linking)은 지원하지 않는다

#### 동행자
- 공유 링크 유입 시 게스트 세션 생성
- 이메일 없이 `nickname + guest token`으로 참여
- 동일 방 재진입을 위해 로컬 스토리지 또는 쿠키에 세션 토큰 저장

#### 권한 규칙
- 방 멤버만 갈등 지도/일정 조회 가능
- 일정 생성/재생성은 방장만 가능
- TPTI 결과 조회는 본인 또는 같은 방 멤버 범위로 제한

### 5.5 방 수명주기 규칙

- 방 생성 직후 상태는 `waiting`
- 참여 인원 2명 이상이며 각 참여자의 방별 TPTI 스냅샷이 존재하면 `ready`
- 일정 생성은 방장이 명시적으로 실행해야 한다
- 일정 생성 후 신규 멤버가 참여하면 기존 일정은 유지하되, 화면에 “새 멤버 반영을 위해 재생성이 필요함” 상태를 표시한다
- MVP에서는 “전원 완료 자동 생성”을 지원하지 않는다

---

## 6. 데이터 모델 명세

### 6.1 핵심 테이블

#### `users`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | 사용자 식별자 |
| `nickname` | varchar(50) | 표시명 |
| `email` | varchar(255) nullable | OAuth 제공 이메일 |
| `auth_provider` | enum | `kakao`, `google`, `guest` |
| `provider_user_id` | varchar(100) nullable | OAuth 공급자 사용자 ID |
| `profile_image_url` | text nullable | OAuth 프로필 이미지 |
| `admin_yn` | char(1) | 관리자 여부 (`Y`/`N`) |
| `is_guest` | boolean | 게스트 여부 |
| `del_yn` | char(1) | 소프트 삭제 여부 (`Y`/`N`) |
| `created_at` | datetime | 생성일 |

#### `tpti_results`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | 결과 식별자 |
| `user_id` | bigint FK | 사용자 |
| `mobility_score` | tinyint unsigned | 0~100 |
| `photo_score` | tinyint unsigned | 0~100 |
| `budget_score` | tinyint unsigned | 0~100 |
| `theme_score` | tinyint unsigned | 0~100 |
| `character_name` | varchar(100) | 자동 생성 별명 |
| `source_answers` | json | 문항 응답 원본 |
| `is_manually_adjusted` | boolean | 수동 조정 여부 |
| `del_yn` | char(1) | 소프트 삭제 여부 (`Y`/`N`) |
| `created_at` | datetime | 생성일 |

#### `trip_rooms`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | 방 식별자 |
| `host_user_id` | bigint FK | 방장 |
| `share_code` | varchar(12) unique | 초대 코드 |
| `destination` | varchar(100) | 여행 지역 |
| `trip_date` | date | 여행 날짜 |
| `status` | enum | `waiting`, `ready`, `completed` |
| `del_yn` | char(1) | 소프트 삭제 여부 (`Y`/`N`) |
| `created_at` | datetime | 생성일 |

#### `room_members`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | 멤버 관계 식별자 |
| `room_id` | bigint FK | 방 |
| `user_id` | bigint FK | 사용자 |
| `role` | enum | `host`, `member` |
| `joined_at` | datetime | 참여 시각 |
| `del_yn` | char(1) | 소프트 삭제 여부 (`Y`/`N`) |

#### `room_member_profiles`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | 방별 프로필 식별자 |
| `room_id` | bigint FK | 방 |
| `user_id` | bigint FK | 사용자 |
| `tpti_result_id` | bigint FK | 원본 TPTI 결과 |
| `mobility_score` | tinyint unsigned | 방 기준 고정 점수 |
| `photo_score` | tinyint unsigned | 방 기준 고정 점수 |
| `budget_score` | tinyint unsigned | 방 기준 고정 점수 |
| `theme_score` | tinyint unsigned | 방 기준 고정 점수 |
| `character_name` | varchar(100) | 방 기준 별명 |
| `del_yn` | char(1) | 소프트 삭제 여부 (`Y`/`N`) |
| `created_at` | datetime | 생성일 |

#### `conflict_maps`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | 갈등 지도 식별자 |
| `room_id` | bigint FK | 방 |
| `common_axes` | json | 공통 지대 축 목록 |
| `conflict_axes` | json | 축별 편차/등급/설명 |
| `summary_text` | text | 자연어 요약 |
| `del_yn` | char(1) | 소프트 삭제 여부 (`Y`/`N`) |
| `created_at` | datetime | 생성일 |

#### `schedules`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | 일정 식별자 |
| `room_id` | bigint FK | 방 |
| `version` | int | 재생성 버전 |
| `generation_input` | json | 생성 조건 스냅샷 |
| `group_satisfaction` | tinyint unsigned | 0~100 |
| `llm_provider` | varchar(50) | 사용 모델 공급자 |
| `del_yn` | char(1) | 소프트 삭제 여부 (`Y`/`N`) |
| `created_at` | datetime | 생성일 |

#### `schedule_slots`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | 슬롯 식별자 |
| `schedule_id` | bigint FK | 일정 |
| `start_time` | datetime | 시작 시각 |
| `end_time` | datetime | 종료 시각 |
| `place_id` | bigint FK | 장소 |
| `slot_type` | enum | `common`, `personal` |
| `target_user_id` | bigint nullable FK | 개인 슬롯 대상 |
| `reason_axis` | varchar(20) | `mobility`, `photo`, `budget`, `theme`, `common` |
| `order_index` | int | 정렬 순서 |
| `del_yn` | char(1) | 소프트 삭제 여부 (`Y`/`N`) |

#### `places`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | 내부 장소 ID |
| `tour_api_id` | varchar(100) unique | TourAPI 원본 ID |
| `name` | varchar(255) | 장소명 |
| `address` | varchar(255) | 주소 |
| `latitude` | decimal(10,7) | 위도 |
| `longitude` | decimal(10,7) | 경도 |
| `category` | varchar(100) | 카테고리 |
| `image_url` | text | 대표 이미지 |
| `operating_hours` | json | 영업 시간 |
| `admission_fee` | varchar(100) | 입장료 텍스트 |
| `mobility_score` | tinyint unsigned | 0~100 |
| `photo_score` | tinyint unsigned | 0~100 |
| `budget_score` | tinyint unsigned | 0~100 |
| `theme_score` | tinyint unsigned | 0~100 |
| `metadata_tags` | json | 부가 태그 |
| `del_yn` | char(1) | 소프트 삭제 여부 (`Y`/`N`) |
| `updated_at` | datetime | 갱신일 |

#### `satisfaction_scores`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | 식별자 |
| `schedule_id` | bigint FK | 일정 |
| `user_id` | bigint FK | 사용자 |
| `score` | tinyint unsigned | 0~100 |
| `breakdown` | json | 축별/슬롯별 점수 상세 |
| `del_yn` | char(1) | 소프트 삭제 여부 (`Y`/`N`) |
| `created_at` | datetime | 생성일 |

### 6.2 권장 인덱스

- `trip_rooms.share_code` unique index
- `users(auth_provider, provider_user_id)` unique index
- `users(email)` index
- `users(del_yn)` index
- `room_members(room_id, user_id)` unique index
- `room_member_profiles(room_id, user_id)` unique index
- `tpti_results.user_id` index
- `schedules(room_id, version)` unique index
- `schedule_slots(schedule_id, order_index)` index
- `places(category)` index
- `places(latitude, longitude)` spatial/복합 index

### 6.3 소프트 삭제 정책

- 모든 핵심 테이블은 기본적으로 `del_yn` 컬럼을 가진다.
- 기본값은 `N`이며, 삭제 시 실제 row 제거 대신 `Y`로 업데이트한다.
- 일반 조회 API/배치/합의 엔진은 기본적으로 `del_yn = 'N'` 조건을 포함해야 한다.
- 물리 삭제는 장기 보관 만료 또는 운영상 purge 작업에서만 수행한다.
- `users.admin_yn`은 관리자 권한 플래그이며 기본값은 `N`이다.

---

## 7. TPTI 검사 기술 명세

### 7.1 문항 구조

- 총 8문항
- 축당 2문항
- 응답 방식: 5점 리커트(1~5)
- 각 문항은 특정 축의 정방향 또는 역방향으로 매핑

### 7.2 점수 계산

각 축 점수는 아래 방식으로 계산한다.

```text
축 점수 = round(((정규화된 문항합 / 최대값) * 100))
```

- 역문항은 제출 시 정방향으로 환산
- 결과는 0~100 정수로 저장
- 수동 조정 기능이 활성화되면 최종 저장 전 사용자 수정값을 우선한다
- 방 참여 시점에는 최신 점수를 `room_member_profiles`로 스냅샷 저장한다
- 이후 개인이 다른 방에서 다시 검사하거나 점수를 수정하더라도 기존 방 일정에는 영향을 주지 않는다

### 7.3 캐릭터 별명 생성 규칙

MVP에서는 LLM 대신 규칙 기반으로 생성한다.

예시 규칙:
- 활동성 상위 + 사진 상위 → `뚜벅이 탐험가`
- 휴식형 + 자연형 → `숲속 휴양가`
- 도심형 + 사진형 → `핫플 기록가`

장점:
- 즉시 생성 가능
- 비용 없음
- 결과 카드 품질 일관성 확보

---

## 8. 갈등 지도 기술 명세

### 8.1 갈등 분석 규칙

각 축에 대해 방 참여자 점수의 `max - min`을 편차로 계산한다.

| 편차 | 등급 | 처리 |
|---|---|---|
| 0~20 | 공통 지대 | 전체 일정 기본 성향으로 반영 |
| 21~40 | 경미한 차이 | 필요 시 1개 개인 슬롯 반영 |
| 41~60 | 조율 필요 | 최소 2개 개인 슬롯 후보 생성 |
| 61~100 | 심각한 충돌 | 우선 배분 축으로 처리 |

### 8.2 갈등 지도 산출물

`conflict_maps.conflict_axes` JSON 예시:

```json
[
  {
    "axis": "mobility",
    "min": 15,
    "max": 85,
    "gap": 70,
    "severity": "critical",
    "members": [
      { "userId": 10, "score": 85 },
      { "userId": 11, "score": 15 }
    ]
  }
]
```

### 8.3 자연어 요약 생성

MVP에서는 템플릿 기반 요약을 사용한다.

예시:
- `A님과 B님은 활동성에서 70점 차이로 충돌합니다.`
- `예산 성향은 전반적으로 비슷하여 공통 지대로 분류됩니다.`

LLM 없이 템플릿으로 처리하면 속도와 일관성이 좋다.

---

## 9. 합의 엔진 기술 명세

### 9.1 입력

- 방 참여자 목록 + 최신 TPTI 결과
- 여행 조건(지역, 날짜, 1일 일정)
- 장소 후보 목록(운영시간/지역/카테고리 필터 적용)
- 갈등 지도 결과

### 9.2 처리 단계

> MVP 원칙: **슬롯 배분, 후보 필터링, 만족도 계산은 서버의 결정론적 로직으로 수행하고, LLM은 검증 가능한 JSON 구조 생성/설명 보조 역할로 제한한다.**

#### Step A. 공통 지대 식별
- 편차 20 이하인 축을 공통 지대로 확정
- 공통 지대는 모든 슬롯 평가 시 기본 가중치로 적용

#### Step B. 충돌 축 우선순위 산정
- 편차 기준 내림차순 정렬
- 편차가 큰 축부터 슬롯 배분 우선권 부여

#### Step C. 슬롯 수 산정
- 기본 일정 길이: 12시간
- 슬롯 크기: 1~3시간
- 목표 슬롯 수: 5~7개
- 기본 정책:
  - 공통 지대가 많으면 긴 공통 슬롯 위주
  - 충돌 축이 크면 짧은 개인 슬롯 비중 증가

#### Step D. 개인 슬롯 배분
- 충돌 축별로 양극단 사용자를 식별
- 강한 충돌 축은 번갈아 만족시키는 방식으로 개인 슬롯을 생성
- 동일 사용자가 연속 2개 초과 개인 슬롯을 독점하지 않도록 제한

#### Step E. 장소 후보 매칭
- 슬롯별 목표 점수 벡터 생성
- `places` 테이블에서 목표 벡터와 유사한 장소를 상위 N개 추출
- 운영시간, 카테고리, 지역, 이동거리 조건을 먼저 필터링한 뒤 유사도 계산

#### Step E-1. 이동시간 휴리스틱
- MVP에서는 외부 길찾기 API를 붙이지 않는다
- 이동시간 30분 이내 제약은 아래 휴리스틱으로 대체한다
  - 동일 권역(예: 공주·부여권 / 태안·보령권 / 천안·아산권 / 서산·당진권 등 사전 정의 클러스터) 우선
  - 두 장소 간 직선거리 6km 이하 우선 허용
  - 조건을 모두 만족하지 못하면 후보 순위를 낮추고 fallback 로직에서 제외
- Phase 2에서 실제 지도 라우팅 API로 대체 가능하도록 인터페이스를 분리한다

#### Step F. LLM 일정 구조 생성
- LLM에는 후보 장소 목록과 슬롯 목적만 전달
- 응답 형식은 자유 텍스트가 아닌 **엄격한 JSON**으로 제한
- 장소 ID는 후보 목록에 있는 값만 허용

#### Step G. 후처리 검증
- 슬롯 길이 1~3시간 준수 확인
- 이전 장소와 다음 장소 이동시간 30분 이하 확인
- 영업시간 충돌 여부 확인
- 그룹 만족도 65 이상 확인

#### Step H. 재조정
- 그룹 만족도 < 65면 최대 3회 재시도
- 재시도 시 낮은 만족도의 사용자 축을 우선 강화

### 9.3 장소 유사도 계산식

MVP는 단순 가중 평균으로 계산한다.

```text
match_score = 1 - (
  |user.mobility - place.mobility| +
  |user.photo - place.photo| +
  |user.budget - place.budget| +
  |user.theme - place.theme|
) / 400
```

- 결과 범위: 0.0 ~ 1.0
- 공통 슬롯은 참여자 평균 점수와 비교
- 개인 슬롯은 `target_user_id` 기준으로 비교

### 9.4 만족도 계산식

```text
slot_satisfaction(user) = slot_match_score × (slot_duration / total_trip_duration)
개인 만족도 = Σ slot_satisfaction(user) × 100
그룹 만족도 = min(개인 만족도들)
```

### 9.5 LLM 요청 규칙

#### 입력 제한
- 후보 장소는 슬롯당 최대 5~8개만 전달
- 전체 프롬프트에 포함되는 장소 수는 30개 이하 권장
- 사용자 닉네임 외 개인정보는 전달하지 않음

#### 출력 스키마

```json
{
  "summary": "오전은 활동형 취향, 오후는 휴식형 취향을 반영한 일정입니다.",
  "slots": [
    {
      "startTime": "09:00",
      "endTime": "11:00",
      "placeId": 101,
      "slotType": "personal",
      "targetUserId": 10,
      "reasonAxis": "mobility",
      "reason": "활동성이 높은 사용자 취향 반영"
    }
  ]
}
```

### 9.6 환각 방지 규칙

1. 후보 목록 외 `placeId`는 허용하지 않는다.
2. 서버는 응답 수신 후 JSON Schema 검증을 수행한다.
3. 검증 실패 시 1회 재호출 후, 그래도 실패하면 규칙 기반 fallback 일정으로 대체한다.

### 9.7 규칙 기반 fallback 일정

LLM 실패 시 아래 순서로 생성한다.
- 공통 지대 점수에 가까운 장소 2개
- 최대 충돌 축 고득점 사용자용 장소 1개
- 최대 충돌 축 저득점 사용자용 장소 1개
- 마감 슬롯은 공통 지대 장소 1개

---

## 10. TourAPI 및 장소 데이터 파이프라인

### 10.1 배치 수집 주기

- 실행 주기: 매일 1회 새벽 배치
- 지역 범위: MVP는 충남권 우선 수집
- 대상 데이터: 관광지, 상세 정보, 행사 정보

### 10.2 수집 파이프라인

```text
TourAPI 수집
→ 응답 정규화
→ places upsert
→ 메타 태깅 대상 선별
→ LLM 또는 규칙 기반 축 점수 부여
→ 검색용 인덱스 갱신
```

### 10.3 메타 태깅 방식

1차 MVP에서는 하이브리드 방식을 사용한다.

- **규칙 기반 우선:** 카테고리/키워드/입장료/실내외 여부로 1차 점수 계산
- **LLM 보정:** 설명 텍스트가 충분한 장소만 LLM으로 세부 조정

장점:
- 비용 절감
- 전체 장소를 즉시 서비스 가능
- LLM 실패 시에도 운영 가능

### 10.4 운영시간 정규화

- TourAPI 운영시간이 텍스트일 수 있으므로 JSON 구조로 정규화 저장
- 파싱 실패 시 `unknown` 상태로 저장
- `unknown` 장소는 일정 후보에서 후순위로 밀어낸다

---

## 11. API 명세

### 11.1 공통 규칙

- Base Path: `/api`
- 응답 포맷:

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

- 실패 포맷:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ROOM_NOT_FOUND",
    "message": "존재하지 않는 여행 방입니다."
  }
}
```

### 11.2 주요 엔드포인트

| Method | Endpoint | 인증 | 설명 |
|---|---|---|---|
| GET | `/api/auth/kakao` | 없음 | 카카오 OAuth 시작 |
| GET | `/api/auth/kakao/callback` | 없음 | 카카오 OAuth 콜백 처리 |
| GET | `/api/auth/google` | 없음 | 구글 OAuth 시작 |
| GET | `/api/auth/google/callback` | 없음 | 구글 OAuth 콜백 처리 |
| POST | `/api/auth/logout` | 로그인 필요 | 로그아웃 (쿠키 만료) |
| POST | `/api/auth/guest` | 없음 | 게스트 세션 생성 |
| GET | `/api/tpti/questions` | 없음 | TPTI 문항 조회 |
| POST | `/api/tpti/submit` | 세션 필요 | 검사 결과 제출 |
| GET | `/api/tpti/result/:userId` | 세션 필요 | TPTI 결과 조회 |
| GET | `/api/share/tpti/:resultId` | 없음 | TPTI 결과 공개 공유 페이지 |
| POST | `/api/rooms` | 방장 | 여행 방 생성 |
| GET | `/api/rooms/:id` | 멤버 | 방 상세 조회 |
| GET | `/api/rooms/share/:shareCode` | 없음 | 공유 링크 유효성/방 요약 조회 |
| POST | `/api/rooms/:shareCode/join` | 게스트/방장 | 방 참여 |
| GET | `/api/rooms/:id/members` | 멤버 | 방 멤버 및 상태 조회 |
| GET | `/api/rooms/:id/conflict-map` | 멤버 | 갈등 지도 조회 |
| POST | `/api/rooms/:id/generate-schedule` | 방장 | 일정 생성 |
| GET | `/api/schedules/:id` | 멤버 | 일정 조회 |
| POST | `/api/schedules/:id/regenerate` | 방장 | 일정 재생성 |
| GET | `/api/share/schedules/:scheduleId` | 없음 | 일정 공개 공유 페이지 |

### 11.3 핵심 DTO 예시

#### TPTI 제출

```json
{
  "answers": [4, 2, 5, 1, 3, 4, 2, 5],
  "manualAdjustments": {
    "mobilityScore": 85,
    "photoScore": 70,
    "budgetScore": 30,
    "themeScore": 60
  }
}
```

#### 방 생성

```json
{
  "destination": "충남",
  "tripDate": "2026-05-02"
}
```

#### 일정 생성

```json
{
  "destination": "충남",
  "tripDate": "2026-05-02",
  "startTime": "09:00",
  "endTime": "21:00"
}
```

#### 일정 조회 응답

```json
{
  "id": 5001,
  "roomId": 101,
  "version": 1,
  "groupSatisfaction": 72,
  "summary": "오전 활동, 오후 휴식 중심의 균형 일정",
  "slots": [
    {
      "orderIndex": 1,
      "startTime": "2026-05-02T09:00:00+09:00",
      "endTime": "2026-05-02T11:00:00+09:00",
      "slotType": "personal",
      "targetUserId": 10,
      "reasonAxis": "mobility",
      "place": {
        "id": 101,
        "name": "북악산 둘레길"
      }
    }
  ],
  "satisfactionByUser": [
    { "userId": 10, "score": 82 },
    { "userId": 11, "score": 72 },
    { "userId": 12, "score": 69 }
  ]
}
```

---

## 12. 백엔드 처리 시퀀스

### 12.1 게스트 참여 플로우

```text
공유 링크 접속
→ shareCode 검증
→ 게스트 세션 생성(auth/guest)
→ TPTI 검사 완료
→ room_member_profiles 스냅샷 생성
→ room join
→ 멤버 상태 업데이트
→ 갈등 지도 재계산
```

### 12.2 일정 생성 플로우

```text
방장 일정 생성 요청
→ 방 상태/참여 인원 검증
→ 방별 TPTI 스냅샷 조회
→ 갈등 분석
→ 장소 후보 필터링
→ 슬롯 배분 초안 생성
→ LLM 호출
→ 서버 검증/재시도
→ 만족도 계산
→ schedules + slots + scores 저장
→ 응답 반환
```

---

## 13. 비기능 요구사항 구현 방안

### 13.1 성능

| 요구사항 | 구현 방안 |
|---|---|
| TPTI 결과 1초 이내 | 점수 계산/별명 생성은 규칙 기반 처리 |
| 일정 생성 5초 이내 | 후보 장소 수 제한, 템플릿 프롬프트, 규칙 기반 사전 필터링 |
| 50명 동시 접속 | API 캐시, DB 인덱스, 과도한 실시간 TourAPI 호출 제거 |

### 13.2 보안

- OAuth state 검증 및 redirect URI allowlist 적용
- OAuth 성공 후 JWT 만료시간 설정(예: 7일)
- 공유 코드 추측 방지를 위해 랜덤 8~10자 영숫자 사용
- API 입력 검증 및 rate limit 적용
- LLM 프롬프트에 이메일/민감정보 제외

### 13.3 개인정보/보관 정책

- 동행자는 이메일 없이도 사용 가능
- 삭제는 기본적으로 소프트 삭제(`del_yn='Y'`)로 처리
- 6개월 지난 방/일정/결과 데이터는 purge 배치에서 물리 삭제 가능
- 분석/통계용 데이터는 익명 집계만 사용

### 13.4 관측성

- API 요청 로그
- TourAPI 수집 성공/실패 로그
- LLM 호출 시간, 실패율, 재시도 횟수 기록
- 일정 생성 실패 사유(error code) 구조화

---

## 14. 권장 디렉터리 구조

### 14.1 `tmti_web`

```text
tmti_web/
├── app/
│   ├── page.tsx
│   ├── tpti/
│   ├── rooms/
│   ├── join/
│   └── share/
├── components/
├── lib/
│   ├── api/
│   ├── auth/
│   └── utils/
├── styles/
└── public/
```

### 14.2 `tmti_server`

```text
tmti_server/
├── src/
│   ├── auth/
│   ├── tpti/
│   ├── room/
│   ├── conflict/
│   ├── consensus/
│   ├── schedule/
│   ├── place/
│   ├── tour-api/
│   ├── llm/
│   └── common/
├── prisma/ or database/
└── test/
```

---

## 15. 테스트 전략

### 15.1 단위 테스트

- TPTI 점수 계산
- 캐릭터 별명 생성
- 갈등 축 분류
- 장소 유사도 계산
- 만족도 계산
- 슬롯 시간 검증

### 15.2 통합 테스트

- 게스트 세션 생성 → TPTI 제출 → 방 참여
- 방 생성 → 멤버 3명 참여 → 갈등 지도 조회
- 일정 생성 → DB 저장 → 일정 조회
- TourAPI upsert 배치

### 15.3 E2E 시나리오

1. 방장 카카오/구글 OAuth 로그인
2. 방 생성 후 링크 복사
3. 게스트 2~3명 링크 참여 및 검사 완료
4. 갈등 지도 확인
5. 일정 생성
6. 일정 공유 링크 확인

### 15.4 MVP 합격 기준

- 3화면 전체 동작
- 3인 그룹 기준 일정 생성 성공
- 그룹 만족도 계산 결과 포함
- 존재하지 않는 장소 추천 없음
- 모바일 웹에서 주요 흐름 오류 없음

---

## 16. 구현 우선순위

### Phase A. 기반 구축
- Next.js/NestJS 프로젝트 초기화
- 인증/게스트 세션
- DB 스키마 생성

### Phase B. TPTI 기능
- 문항 API
- 점수 계산
- 결과 카드

### Phase C. 방/갈등 지도
- 방 생성/참여
- 멤버 상태 관리
- 레이더 차트/갈등 요약

### Phase D. 일정 생성
- 장소 캐시 적재
- 갈등 기반 슬롯 배분
- LLM 일정 생성
- 만족도 계산

### Phase E. 공유/운영
- 공유 링크/OG
- 배치 작업 안정화
- 로그/예외 처리

---

## 17. 결정 사항 및 가정

본 기술 명세서는 PRD를 구현 가능한 수준으로 구체화하기 위해 아래 항목을 추가로 확정했다.

1. 방장 인증 방식은 **카카오 OAuth / 구글 OAuth 전용**으로 한다.
2. TPTI 별명 생성과 갈등 요약은 **규칙/템플릿 기반**으로 시작한다.
3. LLM은 **일정 구조 JSON 생성 전용**으로 제한한다.
4. 장소 메타 태깅은 **규칙 기반 우선 + LLM 보정** 구조를 사용한다.
5. MVP 지역은 **충남권 한정**, 일정 길이는 **1일 고정**이다. Phase 2 이후 수도권 제외 지방 지역으로 순차 확장 예정이다.
6. 일정 생성 실패 시 **규칙 기반 fallback 일정**을 반드시 제공한다.

---

## 18. 후속 문서 권장

다음 문서를 추가하면 구현 효율이 높다.

- `docs/API_SPEC.md` : 요청/응답 DTO 상세 명세
- `docs/DB_SCHEMA.md` : DDL 및 인덱스 명세
- `docs/CONSENSUS_ENGINE.md` : 합의 엔진 알고리즘 상세
- `docs/TEST_PLAN.md` : QA/E2E 테스트 케이스

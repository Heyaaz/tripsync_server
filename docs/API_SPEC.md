# TripSync API 상세 명세서

> 기준 문서: `docs/TECH_SPEC.md`  
> 작성일: 2026-04-02  
> 상태: Draft  
> 범위: TripSync MVP REST API 상세 명세

---

## 1. 문서 목적

본 문서는 TripSync MVP에서 사용하는 인증, TPTI, 방, 갈등 지도, 일정 생성 API의 요청/응답/에러/검증 규칙을 정의한다.

## 2. API 운영 원칙

### 2.1 기본 규칙

- Base URL 예시: `https://api.tripsync.app`
- Base Path: `/api`
- Content-Type: `application/json; charset=utf-8`
- 시간 표기: ISO-8601, 기본 타임존 `Asia/Seoul`
- 언어: 한국어 기본
- API 버전: MVP에서는 URL 버전 미포함, breaking change 발생 시 `/api/v2`로 분리

### 2.2 인증 방식

- 방장 인증은 **Google OAuth 전용**이다.
- 동행자는 `POST /api/auth/guest`로 게스트 세션을 발급받는다.
- 서비스 세션은 JWT 기반이며, 브라우저 환경에서는 **HttpOnly Secure Cookie**를 기본 사용 방식으로 정의한다.
- 프론트엔드는 `credentials: include`로 API를 호출한다.
- 일반 조회 API는 기본적으로 `del_yn='N'` 데이터만 반환한다.

### 2.3 세션 쿠키 규칙

| 쿠키명 | 설명 | 비고 |
|---|---|---|
| `ts_access_token` | 서비스 API 접근용 JWT | HttpOnly, Secure, SameSite=Lax(동일 도메인) 또는 `None`(도메인 분리 시) |
| `ts_guest_session` | 게스트 식별용 보조 토큰(선택) | 게스트 재진입 안정화용 |

### 2.4 공통 응답 형식

성공:

```json
{
  "success": true,
  "data": {},
  "error": null,
  "meta": {
    "requestId": "req_01HX..."
  }
}
```

실패:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ROOM_NOT_FOUND",
    "message": "존재하지 않는 여행 방입니다.",
    "details": null
  },
  "meta": {
    "requestId": "req_01HX..."
  }
}
```

### 2.5 공통 HTTP 상태 코드

| 코드 | 의미 |
|---|---|
| `200` | 조회/처리 성공 |
| `201` | 생성 성공 |
| `302` | OAuth 리다이렉트 |
| `400` | 입력값 오류 |
| `401` | 인증 실패 또는 세션 만료 |
| `403` | 권한 없음 |
| `404` | 리소스 없음 |
| `409` | 중복/상태 충돌 |
| `422` | 비즈니스 규칙 위반 |
| `429` | 호출 제한 초과 |
| `500` | 서버 내부 오류 |
| `502` | 외부 API 연동 실패 |
| `504` | 외부 API 시간 초과 |

### 2.6 공통 에러 코드

| 코드 | 설명 |
|---|---|
| `INVALID_REQUEST` | 필수값 누락 또는 포맷 오류 |
| `UNAUTHORIZED` | 로그인 필요 |
| `FORBIDDEN` | 접근 권한 없음 |
| `SESSION_EXPIRED` | 세션 만료 |
| `ROOM_NOT_FOUND` | 방을 찾을 수 없음 |
| `ROOM_ALREADY_JOINED` | 이미 참여한 방 |
| `ROOM_NOT_READY` | 일정 생성 가능한 상태가 아님 |
| `TPTI_INCOMPLETE` | TPTI 결과가 없는 사용자 존재 |
| `INVALID_SHARE_CODE` | 공유 코드가 유효하지 않음 |
| `OAUTH_STATE_INVALID` | OAuth state 검증 실패 |
| `OAUTH_PROVIDER_ERROR` | OAuth 공급자 응답 오류 |
| `SCHEDULE_GENERATION_FAILED` | 일정 생성 실패 |
| `LLM_INVALID_RESPONSE` | LLM 응답 스키마 오류 |
| `PLACE_CANDIDATE_EMPTY` | 후보 장소 부족 |
| `RESOURCE_DELETED` | soft delete된 리소스 접근 |

---

## 3. 인증 API

### 3.1 POST `/api/auth/register`

일반 회원가입을 처리한다.

- 인증: 없음
- 응답: `201`
- 동작:
  1. 이메일 중복 검사
  2. 비밀번호 해시 생성
  3. `users` 생성 (`auth_provider=local`)
  4. `ts_access_token` 쿠키 설정

#### Request Body

```json
{
  "nickname": "민지",
  "email": "minji@example.com",
  "password": "abc12345"
}
```

#### Validation

- `nickname`: 2~12자
- `email`: 이메일 형식
- `password`: 8~64자, 영문+숫자 포함

### 3.2 POST `/api/auth/login`

일반 로그인을 처리한다.

- 인증: 없음
- 응답: `200`
- 동작:
  1. `auth_provider=local` 사용자 조회
  2. 비밀번호 검증
  3. `ts_access_token` 쿠키 설정

#### Request Body

```json
{
  "email": "minji@example.com",
  "password": "abc12345"
}
```

### 3.3 GET `/api/auth/google`

구글 OAuth 인증을 시작한다.

- 인증: 없음
- 응답: `302 Redirect`
- 동작:
  1. 서버가 `state` 생성
  2. 세션/쿠키에 state 저장
  3. 구글 인증 페이지로 리다이렉트

#### Query Parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `redirectPath` | string | N | 로그인 완료 후 프론트에서 이동할 경로. 기본값 `/rooms/new` |

### 3.4 GET `/api/auth/google/callback`

구글 OAuth 콜백을 처리한다.

- 인증: 없음
- 응답: `302 Redirect`
- 성공 시 동작:
  1. `code`, `state` 검증
  2. 구글 access token 교환
  3. 사용자 정보 조회
  4. `users` upsert (`auth_provider=google`)
  5. `ts_access_token` 쿠키 설정
  6. 프론트 URL로 리다이렉트

#### Query Parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `code` | string | Y | OAuth authorization code |
| `state` | string | Y | CSRF 방지 state |
| `error` | string | N | 공급자 오류 코드 |

#### 성공 리다이렉트 예시

```text
302 Location: https://app.tripsync.app/rooms/new?login=success
Set-Cookie: ts_access_token=...; HttpOnly; Secure
```

### 3.5 POST `/api/auth/logout`

로그인 상태를 해제한다.

- 인증: 로그인 필요
- 응답: `200`
- 동작: `ts_access_token` 쿠키를 만료 처리

#### Response Example

```json
{
  "success": true,
  "data": null,
  "error": null,
  "meta": {
    "requestId": "req_logout_001"
  }
}
```

### 3.6 POST `/api/auth/guest`

게스트 세션을 생성한다.

- 인증: 없음
- 권장 호출 시점: 공유 링크 진입 후 TPTI 검사 시작 직전

#### Request Body

```json
{
  "nickname": "민지",
  "shareCode": "CNAM2026A"
}
```

#### Validation

- `nickname`: 2~12자
- `shareCode`: 선택값, 존재 시 유효성 검증 가능

#### Response `201`

```json
{
  "success": true,
  "data": {
    "user": {
      "id": 301,
      "nickname": "민지",
      "isGuest": true
    },
    "expiresIn": 604800
  },
  "error": null,
  "meta": {
    "requestId": "req_guest_001"
  }
}
```

---

## 4. TPTI API

### 4.1 GET `/api/tpti/questions`

TPTI 문항 목록을 조회한다.

- 인증: 없음
- 응답: `200`
- 비고: MVP에서는 DB 대신 정적 설정 기반 제공 가능

#### Response Example

```json
{
  "success": true,
  "data": {
    "version": "v1",
    "questions": [
      {
        "id": 1,
        "axis": "mobility",
        "reverseScored": false,
        "text": "여행 가면 많이 걷고 여러 장소를 도는 편이 좋다."
      }
    ]
  },
  "error": null,
  "meta": {
    "requestId": "req_tpti_q_001"
  }
}
```

### 4.2 POST `/api/tpti/submit`

TPTI 응답을 제출하고 결과를 생성한다.

- 인증: 방장 또는 게스트 세션 필요
- 응답: `201`

#### Request Body

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

#### Validation

- `answers.length`는 반드시 8
- 각 응답값은 1~5 정수
- `manualAdjustments`는 선택값
- `manualAdjustments`는 기능 플래그가 꺼져 있으면 무시 가능

#### Response `201`

```json
{
  "success": true,
  "data": {
    "resultId": 9001,
    "userId": 301,
    "scores": {
      "mobility": 85,
      "photo": 70,
      "budget": 30,
      "theme": 60
    },
    "characterName": "뚜벅이 탐험가"
  },
  "error": null,
  "meta": {
    "requestId": "req_tpti_submit_001"
  }
}
```

### 4.3 GET `/api/tpti/result/:userId`

사용자의 최신 TPTI 결과를 조회한다.

- 인증: 세션 필요
- 권한: 본인 또는 같은 방 멤버만 가능
- 응답: `200`

#### Path Parameters

| 이름 | 타입 | 설명 |
|---|---|---|
| `userId` | number | 조회 대상 사용자 ID |

#### Response Fields

| 필드 | 설명 |
|---|---|
| `scores` | 4축 점수 |
| `characterName` | 별명 |
| `createdAt` | 결과 생성 시각 |

### 4.4 GET `/api/share/tpti/:resultId`

공유용 TPTI 결과 카드 데이터를 공개 조회한다.

- 인증: 없음
- 응답: `200`
- 용도: `/share/tpti/[resultId]` 공개 페이지/OG 렌더링
- 노출 범위: 공유에 필요한 최소 필드만 반환

#### Path Parameters

| 이름 | 타입 | 설명 |
|---|---|---|
| `resultId` | number | 공개 대상 TPTI 결과 ID |

#### Response Example

```json
{
  "success": true,
  "data": {
    "resultId": 9001,
    "nickname": "민지",
    "characterName": "뚜벅이 탐험가",
    "scores": {
      "mobility": 85,
      "photo": 70,
      "budget": 30,
      "theme": 60
    }
  },
  "error": null,
  "meta": {
    "requestId": "req_share_tpti_001"
  }
}
```

---

## 5. Room API

### 5.1 GET `/api/rooms/:id`

방 ID로 방 상세 정보를 조회한다.

- 인증: 방 멤버 권한 필요
- 응답: `200`

#### Response Example

```json
{
  "success": true,
  "data": {
    "roomId": 101,
    "destination": "충남",
    "tripDate": "2026-05-02",
    "shareCode": "CNAM2026A",
    "status": "ready",
    "hostUserId": 1,
    "memberCount": 3,
    "createdAt": "2026-04-01T10:00:00Z"
  },
  "error": null,
  "meta": {
    "requestId": "req_room_detail_001"
  }
}
```

#### 방 상태 전환 규칙

| 상태 | 전환 조건 |
|------|----------|
| `waiting` | 초기 상태 (방 생성 직후) |
| `ready` | 2명 이상 멤버의 TPTI 스냅샷이 완료된 시점 (`POST /api/tpti/submit` 호출 후 서버가 자동 전환) |
| `completed` | 일정이 생성된 이후 (`POST /api/rooms/:id/generate-schedule` 성공 후 자동 전환) |

### 5.2 POST `/api/rooms`

여행 방을 생성한다.

- 인증: 방장 로그인 필요
- 응답: `201`

#### Request Body

```json
{
  "destination": "충남",
  "tripDate": "2026-05-02"
}
```

#### Validation

- `destination`: MVP에서는 `충남`만 허용
- `tripDate`: 오늘 이후 날짜

#### Response Example

```json
{
  "success": true,
  "data": {
    "roomId": 101,
    "shareCode": "CNAM2026A",
    "status": "waiting"
  },
  "error": null,
  "meta": {
    "requestId": "req_room_create_001"
  }
}
```

### 5.3 GET `/api/rooms/share/:shareCode`

공유 링크 진입 시 방 유효성과 공개 요약 정보를 조회한다.

- 인증: 없음
- 응답: `200`

#### Response Example

```json
{
  "success": true,
  "data": {
    "roomId": 101,
    "destination": "충남",
    "tripDate": "2026-05-02",
    "hostNickname": "지훈",
    "memberCount": 3,
    "status": "waiting"
  },
  "error": null,
  "meta": {
    "requestId": "req_room_share_001"
  }
}
```

### 5.4 POST `/api/rooms/:shareCode/join`

게스트 또는 방장이 공유 코드로 방에 참여한다.

- 인증: 세션 필요
- 응답: `201`

#### Path Parameters

| 이름 | 타입 | 설명 |
|---|---|---|
| `shareCode` | string | 방 초대 코드 |

#### Request Body

```json
{
  "tptiResultId": 9001
}
```

#### 처리 규칙

- 세션 사용자 기준으로 `room_members` upsert
- `tptiResultId`가 있으면 `room_member_profiles` 스냅샷 생성
- 같은 사용자가 재호출하면 멱등(idempotent) 처리

#### Response Example

```json
{
  "success": true,
  "data": {
    "roomId": 101,
    "userId": 301,
    "status": "joined",
    "roomStatus": "ready"
  },
  "error": null,
  "meta": {
    "requestId": "req_room_join_001"
  }
}
```

### 5.5 GET `/api/rooms/:id/members`

방 멤버와 각 멤버의 방별 TPTI 상태를 조회한다.

- 인증: 방 멤버
- 응답: `200`

#### Response Fields

| 필드 | 설명 |
|---|---|
| `members[].userId` | 사용자 ID |
| `members[].nickname` | 닉네임 |
| `members[].role` | host/member |
| `members[].tptiCompleted` | 검사 완료 여부 |
| `members[].scores` | 방별 스냅샷 점수 |

---

## 6. 갈등 지도 API

### 6.1 GET `/api/rooms/:id/conflict-map`

갈등 지도를 조회한다.

- 인증: 방 멤버
- 응답: `200`
- 처리 조건: 방 참여자 2명 이상, 스냅샷 2건 이상

#### Response Example

```json
{
  "success": true,
  "data": {
    "roomId": 101,
    "commonAxes": ["budget"],
    "conflictAxes": [
      {
        "axis": "mobility",
        "gap": 70,
        "severity": "critical"
      }
    ],
    "summaryText": "A님과 B님은 활동성에서 70점 차이로 충돌합니다.",
    "members": [
      {
        "userId": 10,
        "nickname": "지훈",
        "scores": {
          "mobility": 85,
          "photo": 70,
          "budget": 30,
          "theme": 60
        }
      }
    ]
  },
  "error": null,
  "meta": {
    "requestId": "req_conflict_001"
  }
}
```

---

## 7. 일정 생성 API

### 7.1 POST `/api/rooms/:id/generate-schedule`

합의 일정을 생성한다.

- 인증: 방장
- 응답: `201`
- 사전 조건:
  - 방 상태 `ready`
  - 방별 TPTI 스냅샷 2~5개 존재
  - 지역 `충남`

#### Request Body

```json
{
  "destination": "충남",
  "tripDate": "2026-05-02",
  "startTime": "09:00",
  "endTime": "21:00"
}
```

#### Validation

- `destination`은 `충남`만 허용
- `startTime`은 `09:00`만 허용
- `endTime`은 `21:00`만 허용
- 총 여행 시간은 MVP에서 12시간 고정
- 기존 일정이 있어도 새 버전 생성 허용

#### Response Example

```json
{
  "success": true,
  "data": {
    "roomId": 101,
    "version": 1,
    "options": [
      {
        "optionType": "balanced",
        "label": "균형형",
        "summary": "모두가 조금씩 만족하는 안전한 선택",
        "groupSatisfaction": 72,
        "satisfactionByUser": [
          { "userId": 10, "score": 74 },
          { "userId": 11, "score": 70 }
        ]
      },
      {
        "optionType": "individual",
        "label": "개성형",
        "summary": "각자의 취향이 살아있는 교대 배분 일정",
        "groupSatisfaction": 68,
        "satisfactionByUser": [
          { "userId": 10, "score": 82 },
          { "userId": 11, "score": 61 }
        ]
      },
      {
        "optionType": "discovery",
        "label": "지역 발굴형",
        "summary": "충남 인구감소지역 숨은 명소 중심 탐험 일정",
        "groupSatisfaction": 63,
        "satisfactionByUser": [
          { "userId": 10, "score": 71 },
          { "userId": 11, "score": 58 }
        ]
      }
    ]
  },
  "error": null,
  "meta": {
    "requestId": "req_schedule_generate_001"
  }
}
```

### 7.2 GET `/api/schedules/:id`

생성된 일정을 조회한다.

- 인증: 방 멤버
- 응답: `200`

#### Response Shape

```json
{
  "success": true,
  "data": {
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
          "name": "공주 공산성",
          "address": "충남 공주시 웅진로 280"
        }
      }
    ],
    "satisfactionByUser": [
      { "userId": 10, "score": 82 },
      { "userId": 11, "score": 72 },
      { "userId": 12, "score": 69 }
    ]
  },
  "error": null,
  "meta": {
    "requestId": "req_schedule_get_001"
  }
}
```

### 7.3 POST `/api/rooms/:id/confirm-schedule`

그룹이 3가지 옵션 중 하나를 선택하여 일정을 확정한다.

- 인증: 방장
- 응답: `201`
- 사전 조건: 방 상태 `ready`, 일정 옵션이 생성된 상태

#### Request Body

```json
{
  "optionType": "balanced"
}
```

#### Validation

- `optionType`: `balanced` | `individual` | `discovery` 중 하나

#### Response Example

```json
{
  "success": true,
  "data": {
    "scheduleId": 5001,
    "roomId": 101,
    "optionType": "balanced",
    "status": "confirmed"
  },
  "error": null,
  "meta": {
    "requestId": "req_schedule_confirm_001"
  }
}
```

### 7.4 POST `/api/schedules/:id/regenerate`

동일 방의 조건을 기반으로 새 버전의 일정을 재생성한다.

- 인증: 방장
- 응답: `201`

#### Request Body

```json
{
  "reason": "new_member_joined",
  "tripDate": "2026-05-02",
  "startTime": "09:00",
  "endTime": "21:00"
}
```

#### `reason` 허용값

| 값 | 설명 |
|---|---|
| `low_satisfaction` | 만족도 낮음 |
| `new_member_joined` | 신규 멤버 반영 필요 |
| `manual_retry` | 사용자의 수동 재생성 |

#### 처리 규칙

- 같은 방의 새 `version`을 생성한다.
- 이전 일정은 삭제하지 않고 조회 가능 상태로 유지한다.
- `tripDate`만 변경 가능하며, 시간 범위는 MVP에서 항상 `09:00~21:00` 고정이다.

### 7.5 GET `/api/share/schedules/:scheduleId`

공유용 일정 요약 데이터를 공개 조회한다.

- 인증: 없음
- 응답: `200`
- 용도: `/share/schedule/[scheduleId]` 공개 페이지/OG 렌더링
- 노출 범위: 장소 요약, 시간표, 그룹 만족도 등 공유용 최소 정보

#### Path Parameters

| 이름 | 타입 | 설명 |
|---|---|---|
| `scheduleId` | number | 공개 대상 일정 ID |

#### Response Example

```json
{
  "success": true,
  "data": {
    "scheduleId": 5001,
    "destination": "충남",
    "tripDate": "2026-05-02",
    "summary": "오전 활동, 오후 휴식 중심의 균형 일정",
    "groupSatisfaction": 72,
    "slots": [
      {
        "orderIndex": 1,
        "startTime": "09:00",
        "endTime": "11:00",
        "placeName": "공주 마곡사"
      }
    ]
  },
  "error": null,
  "meta": {
    "requestId": "req_share_schedule_001"
  }
}
```

---

## 8. 주요 응답 모델

### 8.1 UserSummary

```json
{
  "id": 10,
  "nickname": "지훈",
  "isGuest": false,
  "authProvider": "google",
  "adminYn": "N"
}
```

### 8.2 TptiScoreSet

```json
{
  "mobility": 85,
  "photo": 70,
  "budget": 30,
  "theme": 60
}
```

### 8.3 ConflictAxis

```json
{
  "axis": "mobility",
  "min": 15,
  "max": 85,
  "gap": 70,
  "severity": "critical"
}
```

### 8.4 ScheduleSlot

```json
{
  "orderIndex": 1,
  "startTime": "2026-05-02T09:00:00+09:00",
  "endTime": "2026-05-02T11:00:00+09:00",
  "slotType": "personal",
  "targetUserId": 10,
  "reasonAxis": "mobility",
  "place": {
    "id": 101,
    "name": "공주 마곡사"
  }
}
```

---

## 9. Rate Limit / 보안 규칙

| 대상 | 제한 |
|---|---|
| OAuth 시작 API | IP 기준 분당 20회 |
| Guest 세션 발급 | IP 기준 분당 10회 |
| TPTI 제출 | 사용자 기준 분당 10회 |
| 일정 생성/재생성 | 방 기준 분당 3회 |

추가 규칙:
- OAuth callback은 반드시 서버가 보관한 `state`와 일치해야 한다.
- `shareCode`는 랜덤 8~10자 영숫자여야 한다.
- 방 멤버 여부는 매 요청마다 서버에서 재검증한다.
- 일정 생성 요청은 동일 방의 동시 중복 실행을 막기 위해 room-level lock을 사용한다.
- 삭제는 기본적으로 soft delete(`del_yn='Y'`)로 처리하고, 삭제된 데이터는 일반 API 응답에서 제외한다.

---

## 10. 미결 사항

다음 항목은 실제 구현 시 추가 확정이 필요하다.

1. 프론트와 API의 실제 도메인 분리 여부에 따른 쿠키 `SameSite` 정책
2. 구글 OAuth scope 최소화 범위
3. 로그인 성공 후 프론트 리다이렉트 경로 세분화
4. 일정 조회 시 이전 버전 목록 API 추가 여부

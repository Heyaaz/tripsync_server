# TripSync DB 스키마 명세서

> 기준 문서: `docs/TECH_SPEC.md`  
> 작성일: 2026-04-02  
> 상태: Draft  
> 범위: TripSync MVP MySQL 스키마 및 제약 정의

---

## 1. 문서 목적

본 문서는 TripSync MVP의 MySQL 스키마, 외래키, 인덱스, 데이터 수명주기, 소프트 삭제 정책을 정의한다.

## 2. DB 기본 정책

| 항목 | 값 |
|---|---|
| DBMS | MySQL 8.x |
| Charset | `utf8mb4` |
| Collation | `utf8mb4_0900_ai_ci` |
| Storage Engine | InnoDB |
| Timezone | `Asia/Seoul` 저장 기준, DB는 UTC 저장 권장 |
| JSON 사용 | 분석 결과/운영시간/원본 응답 저장에 사용 |

### 2.1 네이밍 규칙

- PK는 `id`
- FK는 `{entity}_id`
- 시각 컬럼은 `created_at`, `updated_at` 패턴 우선
- enum은 MySQL `ENUM` 사용
- 기본 삭제는 `del_yn` 기반 **soft delete**
- 물리 삭제는 보관 기간 만료 후 purge 배치에서만 수행

---

## 3. ERD 개요

```text
users
  ├─< tpti_results
  ├─< room_members >─ trip_rooms
  ├─< room_member_profiles >─ trip_rooms
  ├─< satisfaction_scores >─ schedules >─< schedule_slots >─ places
  └──────────────────────────────────────────────┘

trip_rooms
  ├─< conflict_maps
  └─< schedules
```

---

## 4. 테이블 상세 및 DDL

> 아래 DDL은 도메인 이해를 위한 논리 순서로 정리했다. 실제 마이그레이션 실행 순서는 본 문서의 `9. 마이그레이션 순서`를 따른다.

### 4.1 `users`

역할:
- 방장 OAuth 계정 저장
- 게스트 세션용 임시 사용자 저장

```sql
CREATE TABLE users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  nickname VARCHAR(50) NOT NULL,
  email VARCHAR(255) NULL,
  auth_provider ENUM('kakao', 'google', 'guest') NOT NULL,
  provider_user_id VARCHAR(100) NULL,
  profile_image_url TEXT NULL,
  admin_yn CHAR(1) NOT NULL DEFAULT 'N',
  is_guest BOOLEAN NOT NULL DEFAULT FALSE,
  del_yn CHAR(1) NOT NULL DEFAULT 'N',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_users_provider (auth_provider, provider_user_id),
  KEY idx_users_email (email),
  KEY idx_users_del_yn (del_yn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

제약:
- `auth_provider='guest'`인 경우 `provider_user_id`는 NULL 허용
- `auth_provider in ('kakao','google')`인 경우 `provider_user_id`는 필수
- 이메일은 공급자 미제공 가능성을 고려해 unique 제약 대신 일반 index만 사용
- `admin_yn` 기본값은 `N`
- 소프트 삭제 시 `del_yn='Y'`

### 4.2 `tpti_results`

역할:
- 사용자의 개별 TPTI 검사 결과 저장
- 방 참여 시 `room_member_profiles`의 원본으로 참조

```sql
CREATE TABLE tpti_results (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  mobility_score TINYINT UNSIGNED NOT NULL,
  photo_score TINYINT UNSIGNED NOT NULL,
  budget_score TINYINT UNSIGNED NOT NULL,
  theme_score TINYINT UNSIGNED NOT NULL,
  character_name VARCHAR(100) NOT NULL,
  source_answers JSON NOT NULL,
  is_manually_adjusted BOOLEAN NOT NULL DEFAULT FALSE,
  del_yn CHAR(1) NOT NULL DEFAULT 'N',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_tpti_results_user_id (user_id),
  CONSTRAINT fk_tpti_results_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

체크 규칙(애플리케이션 레벨):
- 4개 점수는 0~100
- `source_answers`는 길이 8 배열이어야 함

### 4.3 `trip_rooms`

역할:
- 여행 방 기본 정보 저장

```sql
CREATE TABLE trip_rooms (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  host_user_id BIGINT UNSIGNED NOT NULL,
  share_code VARCHAR(12) NOT NULL,
  destination VARCHAR(100) NOT NULL,
  trip_date DATE NOT NULL,
  status ENUM('waiting', 'ready', 'completed') NOT NULL DEFAULT 'waiting',
  del_yn CHAR(1) NOT NULL DEFAULT 'N',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_trip_rooms_share_code (share_code),
  KEY idx_trip_rooms_host_user_id (host_user_id),
  CONSTRAINT fk_trip_rooms_host_user
    FOREIGN KEY (host_user_id) REFERENCES users(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

상태 의미:
- `waiting`: 멤버 참여 중 / TPTI 미완료 포함
- `ready`: 일정 생성 가능
- `completed`: 일정이 최소 1회 생성된 상태

### 4.4 `room_members`

역할:
- 사용자와 방의 소속 관계 저장

```sql
CREATE TABLE room_members (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  room_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  role ENUM('host', 'member') NOT NULL,
  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  del_yn CHAR(1) NOT NULL DEFAULT 'N',
  PRIMARY KEY (id),
  UNIQUE KEY uq_room_members_room_user (room_id, user_id),
  KEY idx_room_members_user_id (user_id),
  CONSTRAINT fk_room_members_room
    FOREIGN KEY (room_id) REFERENCES trip_rooms(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_room_members_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.5 `room_member_profiles`

역할:
- 방 기준으로 고정된 TPTI 스냅샷 저장
- 이후 사용자의 재검사/수정과 무관하게 일정 생성 일관성 보장

```sql
CREATE TABLE room_member_profiles (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  room_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  tpti_result_id BIGINT UNSIGNED NOT NULL,
  mobility_score TINYINT UNSIGNED NOT NULL,
  photo_score TINYINT UNSIGNED NOT NULL,
  budget_score TINYINT UNSIGNED NOT NULL,
  theme_score TINYINT UNSIGNED NOT NULL,
  character_name VARCHAR(100) NOT NULL,
  del_yn CHAR(1) NOT NULL DEFAULT 'N',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_room_member_profiles_room_user (room_id, user_id),
  KEY idx_room_member_profiles_tpti_result_id (tpti_result_id),
  CONSTRAINT fk_room_member_profiles_room
    FOREIGN KEY (room_id) REFERENCES trip_rooms(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_room_member_profiles_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_room_member_profiles_tpti_result
    FOREIGN KEY (tpti_result_id) REFERENCES tpti_results(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.6 `conflict_maps`

역할:
- 방 단위 갈등 분석 결과 캐시

```sql
CREATE TABLE conflict_maps (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  room_id BIGINT UNSIGNED NOT NULL,
  common_axes JSON NOT NULL,
  conflict_axes JSON NOT NULL,
  summary_text TEXT NOT NULL,
  del_yn CHAR(1) NOT NULL DEFAULT 'N',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_conflict_maps_room_id (room_id),
  CONSTRAINT fk_conflict_maps_room
    FOREIGN KEY (room_id) REFERENCES trip_rooms(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

권장 정책:
- 방당 최신 갈등 지도 1건만 활성 조회 대상으로 사용
- 이력 보존이 필요하면 여러 건 저장 가능

### 4.7 `schedules`

역할:
- 일정 생성 버전 관리

```sql
CREATE TABLE schedules (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  room_id BIGINT UNSIGNED NOT NULL,
  version INT NOT NULL,
  option_type ENUM('balanced', 'individual', 'discovery') NOT NULL,
  is_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  generation_input JSON NOT NULL,
  summary TEXT NULL,
  group_satisfaction TINYINT UNSIGNED NOT NULL,
  llm_provider VARCHAR(50) NULL,
  del_yn CHAR(1) NOT NULL DEFAULT 'N',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_schedules_room_version (room_id, version),
  KEY idx_schedules_room_id (room_id),
  CONSTRAINT fk_schedules_room
    FOREIGN KEY (room_id) REFERENCES trip_rooms(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.8 `schedule_slots`

역할:
- 일정의 시간 슬롯 저장

```sql
CREATE TABLE schedule_slots (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  schedule_id BIGINT UNSIGNED NOT NULL,
  start_time DATETIME NOT NULL,
  end_time DATETIME NOT NULL,
  place_id BIGINT UNSIGNED NOT NULL,
  slot_type ENUM('common', 'personal') NOT NULL,
  target_user_id BIGINT UNSIGNED NULL,
  reason_axis VARCHAR(20) NOT NULL,
  reason_text VARCHAR(100) NULL,
  order_index INT NOT NULL,
  del_yn CHAR(1) NOT NULL DEFAULT 'N',
  PRIMARY KEY (id),
  KEY idx_schedule_slots_schedule_id (schedule_id),
  KEY idx_schedule_slots_schedule_order (schedule_id, order_index),
  KEY idx_schedule_slots_target_user_id (target_user_id),
  CONSTRAINT fk_schedule_slots_schedule
    FOREIGN KEY (schedule_id) REFERENCES schedules(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_schedule_slots_place
    FOREIGN KEY (place_id) REFERENCES places(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_schedule_slots_target_user
    FOREIGN KEY (target_user_id) REFERENCES users(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

애플리케이션 검증:
- `start_time < end_time`
- 슬롯 길이는 60~180분
- `slot_type='common'`이면 `target_user_id`는 NULL

### 4.9 `places`

역할:
- TourAPI 캐시 및 메타 태깅 장소 저장

```sql
CREATE TABLE places (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tour_api_id VARCHAR(100) NOT NULL,
  name VARCHAR(255) NOT NULL,
  address VARCHAR(255) NOT NULL,
  latitude DECIMAL(10,7) NOT NULL,
  longitude DECIMAL(10,7) NOT NULL,
  category VARCHAR(100) NOT NULL,
  image_url TEXT NULL,
  operating_hours JSON NULL,
  admission_fee VARCHAR(100) NULL,
  mobility_score TINYINT UNSIGNED NOT NULL,
  photo_score TINYINT UNSIGNED NOT NULL,
  budget_score TINYINT UNSIGNED NOT NULL,
  theme_score TINYINT UNSIGNED NOT NULL,
  metadata_tags JSON NULL,
  del_yn CHAR(1) NOT NULL DEFAULT 'N',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_places_tour_api_id (tour_api_id),
  KEY idx_places_category (category),
  KEY idx_places_lat_lng (latitude, longitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

운영 규칙:
- `operating_hours`가 `NULL` 또는 `unknown`이면 후보 우선순위를 낮춘다
- 점수 컬럼은 0~100 정수

### 4.10 `satisfaction_scores`

역할:
- 일정 생성 후 사용자별 만족도 저장

```sql
CREATE TABLE satisfaction_scores (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  schedule_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  score TINYINT UNSIGNED NOT NULL,
  breakdown JSON NOT NULL,
  del_yn CHAR(1) NOT NULL DEFAULT 'N',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_satisfaction_scores_schedule_user (schedule_id, user_id),
  KEY idx_satisfaction_scores_user_id (user_id),
  CONSTRAINT fk_satisfaction_scores_schedule
    FOREIGN KEY (schedule_id) REFERENCES schedules(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_satisfaction_scores_user
    FOREIGN KEY (user_id) REFERENCES users(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 5. JSON 컬럼 구조 예시

### 5.1 `tpti_results.source_answers`

```json
[4, 2, 5, 1, 3, 4, 2, 5]
```

### 5.2 `conflict_maps.common_axes`

```json
["budget"]
```

### 5.3 `conflict_maps.conflict_axes`

```json
[
  {
    "axis": "mobility",
    "min": 15,
    "max": 85,
    "gap": 70,
    "severity": "critical"
  }
]
```

### 5.4 `schedules.generation_input`

```json
{
  "destination": "충남",
  "tripDate": "2026-05-02",
  "startTime": "09:00",
  "endTime": "21:00",
  "regenerationReason": "manual_retry"
}
```

### 5.5 `places.operating_hours`

```json
{
  "mon": [{ "open": "09:00", "close": "18:00" }],
  "tue": [{ "open": "09:00", "close": "18:00" }],
  "status": "known"
}
```

### 5.6 `satisfaction_scores.breakdown`

```json
{
  "overall": 72,
  "byAxis": {
    "mobility": 0.78,
    "photo": 0.66,
    "budget": 0.71,
    "theme": 0.74
  },
  "bySlot": [
    {
      "orderIndex": 1,
      "score": 0.84,
      "durationWeight": 0.1667
    }
  ]
}
```

---

## 6. 인덱스 전략

### 6.1 조회 패턴 기준

| 패턴 | 주요 인덱스 |
|---|---|
| OAuth 사용자 조회 | `uq_users_provider(auth_provider, provider_user_id)` |
| 활성 사용자 조회 | `idx_users_del_yn(del_yn)` |
| 공유 코드 진입 | `uq_trip_rooms_share_code(share_code)` |
| 방 멤버 조회 | `uq_room_members_room_user(room_id, user_id)` |
| 방별 고정 프로필 조회 | `uq_room_member_profiles_room_user(room_id, user_id)` |
| 일정 버전 조회 | `uq_schedules_room_version(room_id, version)` |
| 슬롯 렌더링 | `idx_schedule_slots_schedule_order(schedule_id, order_index)` |
| 장소 1차 필터링 | `idx_places_category`, `idx_places_lat_lng` |

### 6.2 추가 최적화 후보

Phase 2 이상에서 검토:
- `places(destination_cluster, category)` 복합 인덱스
- `places(metadata_tags)` 전문 검색 또는 역색인
- MySQL GIS 인덱스(Point 컬럼) 전환

---

## 7. 데이터 수명주기

### 7.1 생성 흐름

```text
OAuth/Guest 세션 생성
→ users 생성
→ TPTI 제출
→ tpti_results 생성
→ 방 참여
→ room_members + room_member_profiles 생성
→ 갈등 지도 생성
→ conflict_maps 생성
→ 일정 생성
→ schedules + schedule_slots + satisfaction_scores 생성
```

### 7.2 삭제 정책

- 모든 핵심 테이블은 기본적으로 `del_yn='N'` 상태로 생성된다.
- 삭제 API/운영 로직은 실제 DELETE 대신 `del_yn='Y'` 업데이트를 수행한다.
- 일반 조회는 반드시 `del_yn='N'`만 대상으로 한다.
- 방, 갈등 지도, 일정, 만족도 데이터는 생성 후 6개월 보관한다.
- 보관 기간 만료 후 soft-deleted 데이터에 한해 purge 배치를 통해 물리 삭제할 수 있다.
- `users`는 다른 데이터와 연결 가능하므로 관리자 승인 없이 물리 삭제하지 않는다.

### 7.3 추천 정리 배치

```sql
UPDATE trip_rooms
SET del_yn = 'Y'
WHERE created_at < (NOW() - INTERVAL 6 MONTH)
  AND del_yn = 'N';
```

### 7.4 purge 배치 예시

```sql
DELETE FROM trip_rooms
WHERE created_at < (NOW() - INTERVAL 6 MONTH)
  AND del_yn = 'Y';
```

---

## 8. 무결성 규칙

1. 한 사용자는 같은 방에 한 번만 참여 가능하다.
2. 한 사용자는 같은 방에 한 개의 스냅샷만 가진다.
3. 일정 버전은 방별로 유일해야 한다.
4. 만족도 점수는 일정-사용자 쌍마다 한 건만 존재한다.
5. 일정 슬롯은 반드시 유효한 장소를 참조해야 한다.
6. 개인 슬롯 대상 사용자는 해당 방의 멤버여야 한다.
7. 게스트 사용자는 `auth_provider='guest'`이며 `is_guest=true`여야 한다.
8. 기본 조회 조건은 `del_yn='N'`이다.
9. 관리자 계정은 `users.admin_yn='Y'`로 구분한다.

---

## 9. 마이그레이션 순서

권장 생성 순서:

1. `users`
2. `tpti_results`
3. `trip_rooms`
4. `room_members`
5. `room_member_profiles`
6. `conflict_maps`
7. `places`
8. `schedules`
9. `schedule_slots`
10. `satisfaction_scores`

purge 역순:

1. `satisfaction_scores`
2. `schedule_slots`
3. `schedules`
4. `places` (독립 테이블이므로 필요 시 별도)
5. `conflict_maps`
6. `room_member_profiles`
7. `room_members`
8. `trip_rooms`
9. `tpti_results`
10. `users`

---

## 10. 미결 사항

1. `places`에 권역(cluster) 컬럼을 추가할지 여부
2. `users.email`의 중복 허용 정책 세부화
3. 일정/갈등 지도 이력 보존 개수 제한 여부
4. 개인정보 삭제 요청 처리 정책(Phase 2)
5. soft-deleted row의 unique key 재사용 정책

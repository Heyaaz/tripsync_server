# TripSync 합의 엔진 상세 명세서

> 기준 문서: `docs/TECH_SPEC.md`  
> 작성일: 2026-04-02  
> 상태: Draft  
> 범위: 그룹 갈등 분석, 슬롯 배분, 장소 선택, 만족도 계산, LLM 경계 정의

---

## 1. 문서 목적

본 문서는 TripSync MVP의 핵심 가치인 “추천이 아니라 합의”를 구현하는 합의 엔진의 입력, 처리 규칙, 산출물, fallback 로직을 상세 정의한다.

## 2. 설계 원칙

1. **결정론 우선**: 갈등 분석, 슬롯 배분, 만족도 계산은 서버 로직으로 수행한다.
2. **LLM 제한 사용**: LLM은 검증 가능한 JSON 구조 생성/설명 보조에만 사용한다.
3. **환각 차단**: 후보 장소 외 placeId는 절대 허용하지 않는다.
4. **최저 만족도 최대화**: 그룹 만족도는 가장 불만족한 사람의 만족도 기준으로 계산한다.
5. **설명 가능성 확보**: 어떤 슬롯이 누구의 어떤 축을 반영했는지 명시한다.

---

## 3. 입력 계약

### 3.1 필수 입력

| 입력 | 설명 |
|---|---|
| 방 정보 | `roomId`, `destination`, `tripDate`, `hostUserId` |
| 방 멤버 스냅샷 | `room_member_profiles` 2~5건 |
| 갈등 지도 | `commonAxes`, `conflictAxes` |
| 장소 후보 풀 | `places` 필터링 결과 |
| 일정 시간 범위 | MVP 고정 `09:00 ~ 21:00` |

### 3.2 멤버 스냅샷 구조

```json
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
```

### 3.3 사전 검증 조건

- 멤버 수는 2~5명
- 모든 멤버는 방별 TPTI 스냅샷 보유
- 후보 장소는 최소 10개 이상 존재 권장
- 지역은 MVP에서 `충남권`만 허용

---

## 4. 축 분석 규칙

### 4.1 축 목록

| 축 | 필드 | 0점 | 100점 |
|---|---|---|---|
| 활동성 | `mobility` | Stay | Walker |
| 기록 | `photo` | Eyes | Artist |
| 예산 | `budget` | Cost-effective | Luxury |
| 테마 | `theme` | Nature | City |

### 4.2 편차 계산

각 축에 대해:

```text
gap(axis) = max(member.score(axis)) - min(member.score(axis))
```

### 4.3 충돌 등급

| gap | 등급 | 내부 severity |
|---|---|---|
| 0~20 | 공통 지대 | `common` |
| 21~40 | 경미한 차이 | `minor` |
| 41~60 | 조율 필요 | `moderate` |
| 61~100 | 심각한 충돌 | `critical` |

### 4.4 분석 결과 산출

- `commonAxes`: gap <= 20 축 배열
- `conflictAxes`: gap > 20 축 배열
- `criticalAxes`: gap >= 61 축 배열
- `priorityAxes`: `conflictAxes`를 gap 내림차순 정렬한 결과

---

## 5. 슬롯 배분 알고리즘

### 5.1 기본 시간 범위

- 여행 시작: `09:00`
- 여행 종료: `21:00`
- 총 시간: `720분`

### 5.2 슬롯 개수 결정

슬롯 수는 갈등 강도에 따라 템플릿으로 결정한다.

| 조건 | 슬롯 수 | 기본 슬롯 길이 템플릿 |
|---|---:|---|
| 갈등 축 0~1개, critical 없음 | 5 | `150,120,150,120,180` |
| 갈등 축 2개 이상 또는 moderate 존재 | 6 | `120,120,120,120,120,120` |
| critical 1개 이상 또는 인원 4명 이상 | 7 | `90,120,90,120,90,120,90` |

규칙:
- 슬롯 길이는 반드시 60~180분
- 슬롯 템플릿 총합은 항상 720분

### 5.3 공통 슬롯 배정 규칙

- 첫 슬롯과 마지막 슬롯은 기본적으로 `common`
- `commonAxes`가 2개 이상이면 मध्य(중간) 슬롯 1개를 추가 공통 슬롯으로 배정할 수 있다
- 공통 슬롯의 목표 벡터는 멤버 평균 점수로 생성한다

### 5.4 개인 슬롯 배정 규칙

공통 슬롯을 제외한 슬롯은 우선순위 축 기준으로 배분한다.

#### 축 가중치

```text
axis_weight = gap(axis) / Σ gap(conflict axes)
```

#### 슬롯 수 배분

```text
personal_slot_count = total_slot_count - common_slot_count
axis_slot_count = round(axis_weight × personal_slot_count)
```

보정 규칙:
- `critical` 축은 최소 1개 슬롯 보장
- 총합이 맞지 않으면 gap이 큰 축부터 1개씩 보정
- 한 사용자에게 연속 3개 개인 슬롯이 배정되면 마지막 슬롯을 다음 우선순위 사용자에게 교체

### 5.5 개인 슬롯 소유자 결정

각 conflict axis마다:
- 최고 점수 사용자 1명
- 최저 점수 사용자 1명
을 식별한다.

개인 슬롯은 다음 순서로 번갈아 배정한다.

```text
high scorer → low scorer → high scorer → low scorer
```

동점 처리:
- 동일 점수 다수일 경우 가장 최근 방 참여 순서가 빠른 사용자 우선

---

## 6. 슬롯별 목표 벡터 생성

### 6.1 공통 슬롯

```text
target_vector_common(axis) = avg(member.score(axis))
```

### 6.2 개인 슬롯

```text
target_vector_personal(axis) = target_user.score(axis)
```

단, 개인 슬롯에서도 공통 지대 축은 유지한다.

예:
- `mobility` 슬롯이 특정 사용자 중심이어도 `budget`이 공통 지대면 평균 예산 성향을 유지

### 6.3 목표 벡터 병합 규칙

```text
merged_target(axis) =
  if axis == reason_axis then target_user_score(axis)
  else if axis in common_axes then avg_score(axis)
  else weighted_midpoint(target_user_score, avg_score)
```

권장 가중치:
- reason axis: 1.0 user score
- common axis: 1.0 average score
- 기타 conflict axis: `0.7 user + 0.3 average`

---

## 7. 장소 후보 선택 알고리즘

### 7.1 1차 필터

아래 조건을 만족하는 `places`만 후보로 사용한다.

- 목적지 = 충남권
- 좌표 존재
- 메타 태깅 점수 4축 존재
- 운영시간이 known이거나 최소한 영업 가능성이 높은 장소

### 7.2 이동시간 휴리스틱

MVP에서는 외부 길찾기 API를 사용하지 않는다.

우선순위 규칙:
1. 동일 권역(cluster) 우선
2. 직선거리 6km 이하 우선
3. 이전 슬롯 장소와 동일 카테고리 연속 2회는 감점

후보 제외 규칙:
- 직전 장소와 너무 멀어 30분 제약을 충족할 가능성이 낮은 장소
- 슬롯 시간 동안 운영하지 않는 장소

### 7.3 유사도 계산식

```text
match_score = 1 - (
  |target.mobility - place.mobility| +
  |target.photo - place.photo| +
  |target.budget - place.budget| +
  |target.theme - place.theme|
) / 400
```

결과 범위:
- `0.0 ~ 1.0`

### 7.4 추가 가점/감점

| 조건 | 점수 조정 |
|---|---:|
| 동일 권역 | `+0.05` |
| 운영시간 known & 완전 일치 | `+0.05` |
| 운영시간 unknown | `-0.08` |
| 직전 슬롯과 거리 경계값 근접 | `-0.05` |
| 동일 카테고리 2회 연속 | `-0.03` |

최종 점수:

```text
final_candidate_score = clamp(match_score + bonus - penalty, 0, 1)
```

### 7.5 후보 개수 제한

- 슬롯별 상위 8개 후보만 LLM/후처리 단계로 전달
- 전체 프롬프트 후보 개수는 30개 이하 유지

---

## 8. LLM 역할과 경계

### 8.1 LLM 사용 목적

LLM은 다음만 수행한다.
- 후보 장소 중 자연스러운 순서 선택
- 일정 요약 문장 생성
- 슬롯별 설명 문장 생성

LLM이 수행하지 않는 것:
- 갈등 축 판정
- 슬롯 개수/길이 결정
- 만족도 계산
- 후보 외 장소 생성

### 8.2 입력 구조

```json
{
  "room": { "id": 101, "destination": "충남", "tripDate": "2026-05-02" },
  "commonAxes": ["budget"],
  "priorityAxes": ["mobility", "theme"],
  "slotPlan": [
    {
      "orderIndex": 1,
      "startTime": "09:00",
      "endTime": "11:00",
      "slotType": "personal",
      "targetUserId": 10,
      "reasonAxis": "mobility",
      "candidatePlaceIds": [101, 102, 103]
    }
  ]
}
```

### 8.3 출력 스키마

```json
{
  "summary": "오전에는 활동형, 오후에는 휴식형 취향을 반영한 균형 일정입니다.",
  "slots": [
    {
      "orderIndex": 1,
      "placeId": 101,
      "reason": "활동성이 높은 사용자의 취향을 반영한 산책형 코스"
    }
  ]
}
```

### 8.4 검증 규칙

서버는 LLM 응답 수신 후 아래를 검증한다.

1. JSON 파싱 가능 여부
2. `orderIndex`가 슬롯 계획과 일치하는지
3. `placeId`가 해당 슬롯 후보 목록 안에 있는지
4. 중복 장소 과다 여부
5. 설명 문자열 길이 제한(예: 80자 이하)

검증 실패 시:
- 1회 재호출
- 그래도 실패 시 fallback 엔진 실행

---

## 9. 만족도 계산

### 9.1 슬롯 만족도

각 사용자에 대해 슬롯별 만족도는 다음과 같이 계산한다.

```text
slot_satisfaction(user, slot) = slot_match_score(user, slot.place) × (slot_duration / 720)
```

### 9.2 사용자별 장소 적합도

```text
slot_match_score(user, place) = 1 - (
  |user.mobility - place.mobility| +
  |user.photo - place.photo| +
  |user.budget - place.budget| +
  |user.theme - place.theme|
) / 400
```

### 9.3 개인 만족도 / 그룹 만족도

```text
personal_satisfaction(user) = Σ slot_satisfaction(user, slot) × 100
group_satisfaction = min(personal_satisfaction(all users))
```

### 9.4 통과 기준

- 그룹 만족도 >= `65` 통과
- 미달 시 최대 3회 재조정

### 9.5 재조정 규칙

재조정 대상 선정:
- 가장 낮은 만족도 사용자 1명 선택
- 그 사용자의 reason axis 반영 슬롯 비중을 계산
- 부족한 축을 다음 시도에서 1슬롯 우선 배정

조정 순서:
1. unhappy user 관련 축 우선순위 상승
2. 후보 장소 상위 K 재선정
3. LLM 재호출 또는 fallback 재실행

---

## 10. fallback 엔진

LLM 실패 또는 만족도 기준 미달 시 규칙 기반 fallback을 사용한다.

### 10.1 fallback 트리거

- 후보 검증 실패 2회
- LLM timeout
- `group_satisfaction < 65`가 3회 연속 발생
- 후보 장소 수 부족으로 일반 알고리즘 실행 불가

### 10.2 fallback 구성 규칙

기본 슬롯 배치:
1. 공통 지대 기반 장소 2개
2. 최대 conflict axis 고득점 사용자용 장소 1개
3. 최대 conflict axis 저득점 사용자용 장소 1개
4. 마지막 슬롯은 공통 지대 장소 1개

6~7 슬롯일 경우:
- 다음 priority axis를 반영한 보조 슬롯 1~2개 추가

### 10.3 fallback 장점

- 외부 모델 실패에도 데모 가능
- 결과 구조가 항상 동일
- 테스트/회귀 검증이 쉬움

---

## 11. 3가지 옵션 생성 전략

합의 엔진은 단일 일정이 아닌 특성이 다른 3가지 옵션을 동시에 생성한다. 각 옵션은 동일한 입력(TPTI 스냅샷, 갈등 지도, 장소 후보)을 사용하되, 슬롯 배분 전략과 장소 선택 가중치가 다르다.

### 11.1 옵션별 생성 전략

| 옵션 | 유형 | `option_type` | 핵심 전략 |
|------|------|---------------|---------|
| A | 균형형 | `balanced` | 슬롯 목표 벡터를 전원 평균값으로 구성. 최저 만족도 최대화 |
| B | 개성형 | `individual` | 충돌 축 교대 배분(Time Slicing). 각자의 순간 보장 |
| C | 지역 발굴형 | `discovery` | 인구감소지역 숨은 명소 최소 1곳 강제 포함. 취향 매칭 + 발굴 가중치 적용 |

### 11.2 옵션별 슬롯 목표 벡터 차이

**A (균형형):**
```text
target_vector(axis) = avg(all member scores, axis)
```
모든 슬롯을 전원 평균 벡터로 구성. 충돌 축도 평균값을 기준으로 장소 선택.

**B (개성형):**
```text
개인 슬롯: target_vector(axis) = 해당 슬롯 소유자 점수  (기존 Time Slicing 방식)
공통 슬롯: target_vector(axis) = avg(all member scores, axis)
```
충돌 축 교대 배분 알고리즘(섹션 5)을 그대로 사용.

**C (지역 발굴형):**
```text
B와 동일한 슬롯 배분 방식 + 인구감소지역 가중치 강화
```
- 인구감소지역 장소의 `match_score`에 `+0.20` 추가 가산
- 전체 슬롯 중 최소 1개는 인구감소지역 장소로 고정
- 앵커 장소 20km 이내 인구감소지역 장소는 `+0.15` 추가

### 11.3 옵션 생성 순서

3가지 옵션은 병렬로 생성하지 않고 아래 순서로 생성한다.

1. **B (개성형)** 먼저 생성 — 기존 Time Slicing 로직이 기준
2. **A (균형형)** 생성 — B의 슬롯 구조를 재사용하되 목표 벡터만 평균으로 교체
3. **C (지역 발굴형)** 생성 — B의 슬롯 구조 + 인구감소지역 가중치 강화 후 장소 재선택

### 11.4 만족도 통과 기준 (옵션별)

| 옵션 | 통과 기준 |
|------|---------|
| A (균형형) | `min(personal_satisfaction)` >= 65 |
| B (개성형) | `min(personal_satisfaction)` >= 60 (교대 배분 특성상 편차 허용) |
| C (지역 발굴형) | `min(personal_satisfaction)` >= 55 (발굴 목적상 완화) |

---

## 12. 출력 계약

합의 엔진 최종 출력 — 3가지 옵션 배열:

```json
{
  "options": [
    {
      "optionType": "balanced",
      "label": "균형형",
      "summary": "모두가 조금씩 만족하는 안전한 선택",
      "groupSatisfaction": 72,
      "version": 1,
      "slots": [
        {
          "orderIndex": 1,
          "slotType": "common",
          "targetUserId": null,
          "reasonAxis": "budget",
          "startTime": "09:00",
          "endTime": "11:00",
          "placeId": 101,
          "reason": "그룹 전원의 평균 취향 반영"
        }
      ],
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
      "version": 1,
      "slots": [
        {
          "orderIndex": 1,
          "slotType": "personal",
          "targetUserId": 10,
          "reasonAxis": "mobility",
          "startTime": "09:00",
          "endTime": "11:00",
          "placeId": 102,
          "reason": "활동성 취향 반영"
        }
      ],
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
      "version": 1,
      "slots": [
        {
          "orderIndex": 2,
          "slotType": "personal",
          "targetUserId": 10,
          "reasonAxis": "mobility",
          "startTime": "11:00",
          "endTime": "13:00",
          "placeId": 205,
          "reason": "인구감소지역 숨은 명소 — 활동성 취향과 매칭",
          "isHiddenGem": true
        }
      ],
      "satisfactionByUser": [
        { "userId": 10, "score": 71 },
        { "userId": 11, "score": 58 }
      ]
    }
  ]
}
```

---

## 14. 관측성 및 로깅

각 일정 생성 요청에 대해 아래 값을 기록한다.

| 항목 | 설명 |
|---|---|
| `request_id` | 요청 추적 ID |
| `room_id` | 방 ID |
| `member_count` | 멤버 수 |
| `conflict_axes_count` | 충돌 축 개수 |
| `slot_count` | 생성 슬롯 수 |
| `llm_provider` | 사용 모델 |
| `llm_latency_ms` | 모델 응답 시간 |
| `fallback_used` | fallback 사용 여부 |
| `group_satisfaction` | 최종 그룹 만족도 |
| `failure_reason` | 실패 시 이유 |

---

## 15. 테스트 포인트

1. 같은 입력에 대해 결정론 단계 결과가 항상 동일해야 한다.
2. `placeId`가 후보 목록 밖으로 나가지 않아야 한다.
3. 슬롯 길이는 60~180분을 유지해야 한다.
4. 그룹 만족도 계산은 사용자 수가 바뀌어도 일관되게 동작해야 한다.
5. fallback 결과도 동일한 출력 스키마를 만족해야 한다.

---

## 16. 미결 사항

1. 권역(cluster) 정의 기준의 상세화
2. 행사/축제 데이터를 일반 장소와 어떻게 병합할지

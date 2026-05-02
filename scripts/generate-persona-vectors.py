#!/usr/bin/env python3
"""
TripSync Persona Vector Generator

nvidia/Nemotron-Personas-Korea 데이터셋을 TPTI 4축 벡터로 사전 매핑합니다.
빌드/CI 환경에서 1회 실행되며, 서버 배포 패키지에 포함됩니다.

Usage:
    python generate-persona-vectors.py [--output persona-vectors.json]

Output:
    persona-vectors.json - {uuid: {mobility, photo, budget, theme}} 형태의 벡터 파일
"""

import json
import re
import sys
from pathlib import Path
from typing import Dict, Optional


# TPTI 4축 키워드 사전
KEYWORDS = {
    "mobility": {
        "high": ["등산", "트레킹", "하이킹", "산책", "둘레길", "탐방", "탐험", "시티투어", "구석구석", "돌아다니기", "걷기", "체험", "액티비티"],
        "low": ["호캉스", "휴식", "휴양", "리조트", "숙소", "조용히", "머무르기", "쉬기", "느긋하게", "여유롭게", "힐링"],
        "mid": ["여행", "관광", "명소", "풍경", "구경"]
    },
    "photo": {
        "high": ["사진", "인생샷", "출사", "포토", "침라", "기록", "SNS", "올리기", "촬영", "풍경사진"],
        "low": ["눈으로", "담기", "실속", "느낌", "기억", "체험 중심", "사진은 패스"],
        "mid": ["풍경", "경치", "명승", "예쁜", "감성"]
    },
    "budget": {
        "high": ["고급", "럭셔리", "프리미엄", "맛집", "유명", "인기", "가심비", "플렉스", "아낌없이"],
        "low": ["가성비", "저렴", "알뜰", "절약", "아끼다", "물가 걱정", "배달음식", "대중사우나"],
        "mid": ["외식", "식당", "먹거리", "카페", "맛보기"]
    },
    "theme": {
        "high": ["도심", "핫플", "카페", "맛집", "쇼핑", "도시", "시티", "야경", "번화가", "문화공간", "박물관", "전시"],
        "low": ["자연", "산", "바다", "숲", "계곡", "호수", "힐링", "공원", "캠핑", "생태", "자연경관"],
        "mid": ["역사", "유적", "문화", "전통", "사찰", "고택"]
    }
}

# 구조화 필드 보정 규칙
STRUCTURE_RULES = {
    "age": {
        "young": (19, 29, {"mobility": 10, "budget": -5}),
        "middle": (30, 49, {}),
        "old": (50, 99, {"mobility": -10, "budget": 5})
    },
    "housing_type": {
        "one_room": ("원룸", "오피스텔", "고시원"),
        "adjustment": {"budget": -5}
    },
    "family_type": {
        "alone": ("혼자 거주", {"mobility": 5}),
        "family": ("배우자·자녀와 거주", {"mobility": -5, "budget": 5})
    }
}


def calculate_axis_score(text: str, axis: str) -> int:
    """텍스트를 기반으로 한 축 점수를 계산합니다."""
    if not text:
        return 50

    text_lower = text.lower()
    score = 50

    axis_keywords = KEYWORDS[axis]

    for keyword in axis_keywords["high"]:
        if keyword in text or keyword in text_lower:
            score += 15

    for keyword in axis_keywords["low"]:
        if keyword in text or keyword in text_lower:
            score -= 15

    # 중간 키워드는 상하 키워드가 없을 때만 가산
    if 30 < score < 70:
        for keyword in axis_keywords["mid"]:
            if keyword in text or keyword in text_lower:
                score += 5

    return max(0, min(100, score))


def needs_llm_fallback(texts: Dict[str, str], scores: Dict[str, int]) -> bool:
    """LLM fallback이 필요한지 판단합니다."""
    combined_text = " ".join(str(t) for t in texts.values() if t)

    # 1. 모든 축이 40~60에 몰림 (분산 없음)
    if all(40 <= s <= 60 for s in scores.values()):
        return True

    # 2. 텍스트에 매핑 사전 키워드가 거의 없음
    total_keywords = 0
    for axis_keywords in KEYWORDS.values():
        for category in ["high", "low", "mid"]:
            for keyword in axis_keywords[category]:
                if keyword in combined_text:
                    total_keywords += 1

    if total_keywords < 2:
        return True

    # 3. 동일 축에 상반된 키워드가 모두 등장
    for axis in ["mobility", "photo", "budget", "theme"]:
        axis_keywords = KEYWORDS[axis]
        has_high = any(kw in combined_text for kw in axis_keywords["high"])
        has_low = any(kw in combined_text for kw in axis_keywords["low"])
        if has_high and has_low:
            return True

    return False


def apply_structure_adjustment(
    scores: Dict[str, int],
    age: Optional[int] = None,
    occupation: Optional[str] = None,
    housing_type: Optional[str] = None,
    family_type: Optional[str] = None
) -> Dict[str, int]:
    """구조화 필드를 기반으로 점수를 보정합니다."""
    adjusted = dict(scores)

    # Age 보정
    if age is not None:
        if 19 <= age <= 29:
            adjusted["mobility"] = min(100, adjusted["mobility"] + 10)
            adjusted["budget"] = max(0, adjusted["budget"] - 5)
        elif age >= 50:
            adjusted["mobility"] = max(0, adjusted["mobility"] - 10)
            adjusted["budget"] = min(100, adjusted["budget"] + 5)

    # Housing type 보정
    if housing_type and housing_type in STRUCTURE_RULES["housing_type"]:
        for key, value in STRUCTURE_RULES["housing_type"]["adjustment"].items():
            adjusted[key] = max(0, min(100, adjusted[key] + value))

    # Family type 보정
    if family_type:
        family_rules = STRUCTURE_RULES["family_type"]
        if family_type == family_rules["alone"]:
            for key, value in family_rules["alone"].items():
                adjusted[key] = max(0, min(100, adjusted[key] + value))
        elif family_type == family_rules["family"]:
            for key, value in family_rules["family"].items():
                adjusted[key] = max(0, min(100, adjusted[key] + value))

    # Occupation 보정 (간단히)
    if occupation:
        high_budget_jobs = ["임원", "사업가", "전문의", "변호사", "의사"]
        low_budget_jobs = ["학생", "신입사원", "인턴", "아륰바이트"]

        if any(job in occupation for job in high_budget_jobs):
            adjusted["budget"] = min(100, adjusted["budget"] + 10)
        elif any(job in occupation for job in low_budget_jobs):
            adjusted["budget"] = max(0, adjusted["budget"] - 10)

    return adjusted


def process_persona_row(row: Dict) -> Optional[Dict[str, int]]:
    """단일 persona row를 TPTI 4축 벡터로 변환합니다."""
    try:
        # 텍스트 필드 결합
        texts = {
            "travel": str(row.get("travel_persona", "")),
            "sports": str(row.get("sports_persona", "")),
            "arts": str(row.get("arts_persona", "")),
            "culinary": str(row.get("culinary_persona", "")),
            "hobbies": str(row.get("hobbies_and_interests", ""))
        }

        combined_text = " ".join(texts.values())

        # 규칙 기반 점수 계산
        scores = {
            "mobility": calculate_axis_score(combined_text, "mobility"),
            "photo": calculate_axis_score(combined_text, "photo"),
            "budget": calculate_axis_score(combined_text, "budget"),
            "theme": calculate_axis_score(combined_text, "theme")
        }

        # LLM fallback 체크
        if needs_llm_fallback(texts, scores):
            # MVP에서는 fallback row는 기본값(50,50,50,50)으로 처리
            # 실제 구현 시 LLM API 호출 또는 별도 처리 필요
            scores = {
                "mobility": 50,
                "photo": 50,
                "budget": 50,
                "theme": 50
            }

        # 구조화 필드 보정
        age = row.get("age")
        occupation = str(row.get("occupation", "")) if row.get("occupation") else None
        housing_type = str(row.get("housing_type", "")) if row.get("housing_type") else None
        family_type = str(row.get("family_type", "")) if row.get("family_type") else None

        scores = apply_structure_adjustment(
            scores, age=age, occupation=occupation,
            housing_type=housing_type, family_type=family_type
        )

        return scores

    except Exception as e:
        print(f"Error processing row: {e}", file=sys.stderr)
        return None


def generate_vectors(input_path: str, output_path: str, limit: Optional[int] = None):
    """persona-vectors.json을 생성합니다."""
    print(f"Reading dataset from: {input_path}")

    try:
        import pandas as pd
    except ImportError:
        print("Error: pandas required. pip install pandas pyarrow", file=sys.stderr)
        sys.exit(1)

    # Parquet 파일 읽기
    df = pd.read_parquet(input_path)

    if limit:
        df = df.head(limit)

    total = len(df)
    print(f"Total rows: {total}")

    vectors = {}
    fallback_count = 0

    for idx, row in df.iterrows():
        if idx % 10000 == 0:
            print(f"Processing... {idx}/{total} ({idx/total*100:.1f}%)")

        uuid = str(row.get("uuid", ""))
        if not uuid:
            continue

        scores = process_persona_row(row.to_dict())
        if scores:
            # LLM fallback 카운트 (50,50,50,50인 경우)
            if all(v == 50 for v in scores.values()):
                fallback_count += 1

            vectors[uuid] = scores

    # 통계 출력
    print(f"\nProcessed: {len(vectors)} vectors")
    print(f"LLM fallback: {fallback_count} ({fallback_count/len(vectors)*100:.1f}%)")

    # 4축 평균 계산
    avg_scores = {
        axis: sum(v[axis] for v in vectors.values()) / len(vectors)
        for axis in ["mobility", "photo", "budget", "theme"]
    }
    print(f"Average scores: {avg_scores}")

    # 저장
    output_file = Path(output_path)
    output_file.parent.mkdir(parents=True, exist_ok=True)

    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(vectors, f, ensure_ascii=False, indent=None)

    file_size = output_file.stat().st_size / (1024 * 1024)
    print(f"\nSaved to: {output_path}")
    print(f"File size: {file_size:.1f} MB")


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Generate persona vectors from Nemotron dataset")
    parser.add_argument("--input", default="hf://datasets/nvidia/Nemotron-Personas-Korea/data/train-*.parquet",
                        help="Input parquet file path or HuggingFace URL")
    parser.add_argument("--output", default="../assets/persona-vectors.json",
                        help="Output JSON file path (default: ../assets/persona-vectors.json)")
    parser.add_argument("--limit", type=int, default=None,
                        help="Limit number of rows to process (for testing)")

    args = parser.parse_args()

    generate_vectors(args.input, args.output, args.limit)


if __name__ == "__main__":
    main()

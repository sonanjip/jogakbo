# 조각보 (Jogakbo)

[English](README.md)

AI가 폰 안에서 돌아가는 안드로이드 노트 앱입니다. 직접 쓴 메모, 웹페이지, 유튜브 영상, 녹음을 모으고, 노트마다 위에 요약, 아래에 원문을 둡니다. **조각보**는 자투리 천을 이어 만든 보자기입니다. 웹, 영상, 녹음, 메모의 조각을 한곳에 모아 찾아볼 수 있게 합니다. *Journal Of Gathered Audio, Knowledge, Bookmarks & Originals.*

| 부분 | 앱 | 하는 일 |
|---|---|---|
| 노트 | **조각보** (이 저장소) | 노트, 요약, 녹음, 검색, 내보내기·가져오기 |
| AI 모델 | [온돌샘](https://github.com/TaeHunKim/ondolsaem) (AI 기능에 필요) | 언어 모델(요약), 음성 인식, 임베딩 모델(검색)을 폰에서 실행 |
| 채팅 | [말벗다리](https://github.com/TaeHunKim/malbotdari) (선택) | 조각보 SDK로 채팅에서 노트를 검색하고 읽음 |

## 기능

| 기능 | 설명 |
|---|---|
| 메모 | 서식 도구 모음(제목, 굵게, 목록, 체크리스트, 인용, 코드, 링크)과 미리보기가 있는 마크다운 노트, 원하면 요약 |
| 웹페이지 | 링크를 공유하거나 붙여 넣으면 본문을 마크다운으로 뽑아 요약 |
| 유튜브 | 제목, 채널, 설명, 자막(직접 단 것 또는 자동 생성)을 저장하고 요약. 자막 줄을 누르면 그 시각부터 영상이 열림 |
| 녹음 | 앱에서 녹음(녹음하는 동안 받아쓴 줄이 차례로 나타남)하거나 오디오 파일을 가져와 받아쓰고 요약. 줄을 누르면 그 위치부터 재생 |
| 요약 | 모든 요약에 쓸 지시문과 요약마다 쓸 지시문, 원문 언어 또는 앱 언어. 요약은 고칠 수 있고, 고친 요약을 다시 요약할 때는 먼저 묻습니다 |
| 검색 | 의미(폰에서 만든 임베딩, 시점은 설정에서 선택)와 단어로 |
| 내보내기·가져오기 | 노트마다 마크다운 파일 하나가 든 ZIP(원하면 오디오 포함). 그런 ZIP이나 마크다운·텍스트 파일을 가져옴 |
| 다른 앱 | 말벗다리(와 허용한 앱)가 SDK(`:sdk`)로 노트를 검색하고 읽음 |

영어·한국어 UI, 다크 모드.

## 요구 사항

Android 10 이상(minSdk 29). 온돌샘에 언어 모델(요약), 음성 인식 모델(녹음), 임베딩 모델(의미 검색)이 있어야 합니다. 메모 쓰기, 링크 저장, 단어 검색, 내보내기는 온돌샘 없이도 됩니다.

## 빌드

```sh
source scripts/env.sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

온돌샘 SDK는 JitPack에서 받습니다. 형제 폴더의 소스로 빌드하는 법은 [docs/development.md](docs/development.md).

## 문서

[사용 안내](docs/user-guide.md) · [구조](docs/architecture.md) · [노트 SDK](docs/notes-sdk.md) · [개인정보](docs/privacy.md) · [개발](docs/development.md) · [로드맵](docs/roadmap.md)

## 라이선스

Apache License 2.0. [LICENSE](LICENSE)와 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 참고.

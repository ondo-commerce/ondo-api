# ─────────────────────────────────────────────────────────────
# B · S3 — 사용자가 올리는 파일
#
# 두 종류의 성격이 달라서 버킷을 나눈다. 한 버킷에 접두사로 가르면 공개 정책을
# 접두사 단위로 관리해야 하는데, 그건 실수 한 번에 증빙 서류가 열린다.
#
#   상품 이미지    도매가 올리고 소매가 본다.   나중에 공개 읽기로 열 수 있다
#   증빙 서류      소매가 올리고 운영자만 본다.  절대 안 연다
#
# 상품 이미지 버킷은 시드(V903)의 상품 사진을 서빙한다. 도매 화면의 업로드는 아직 없다.
# 증빙 서류 버킷은 소매 가입이 쓴다(MUL-100).
# ─────────────────────────────────────────────────────────────

# 버킷 이름은 전 세계에서 유일해야 한다. 계정 번호를 붙여 겹치지 않게 한다
data "aws_caller_identity" "current" {}

locals {
  bucket_suffix = data.aws_caller_identity.current.account_id
}

# ── 상품 이미지 ──────────────────────────────────────────────
resource "aws_s3_bucket" "listing_images" {
  bucket = "${local.prefix}-listing-images-${local.bucket_suffix}"
  tags   = { Name = "${local.prefix}-listing-images" }
}

# ── 증빙 서류 — 사업자등록증 ─────────────────────────────────
resource "aws_s3_bucket" "documents" {
  bucket = "${local.prefix}-documents-${local.bucket_suffix}"
  tags   = { Name = "${local.prefix}-documents" }
}

# ── 둘 다 닫아 둔다 ──────────────────────────────────────────
#
# 상품 이미지는 소매 마켓의 <img> 가 바로 읽는다. 그래서 listings/ 아래의 읽기만 연다.
# ACL 은 계속 막는다 — 공개 여부는 아래 버킷 정책 한 곳에서만 정한다.
# 시연(최종 평가) 규모라 CloudFront 없이 S3 주소를 그대로 쓴다. 트래픽이 생기면 앞에 붙인다.
resource "aws_s3_bucket_public_access_block" "listing_images" {
  bucket                  = aws_s3_bucket.listing_images.id
  block_public_acls       = true
  block_public_policy     = false
  ignore_public_acls      = true
  restrict_public_buckets = false
}

data "aws_iam_policy_document" "listing_images_public_read" {
  statement {
    sid       = "PublicReadListingImages"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.listing_images.arn}/listings/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}

resource "aws_s3_bucket_policy" "listing_images_public_read" {
  bucket = aws_s3_bucket.listing_images.id
  policy = data.aws_iam_policy_document.listing_images_public_read.json

  # 정책이 공개 정책이라, 차단을 먼저 풀어야 들어간다
  depends_on = [aws_s3_bucket_public_access_block.listing_images]
}

resource "aws_s3_bucket_public_access_block" "documents" {
  bucket                  = aws_s3_bucket.documents.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ── 저장할 때 암호화 ─────────────────────────────────────────
# 증빙 서류는 개인정보에 가깝다. 상품 이미지도 같이 켠다 — 공짜고 끌 이유가 없다
resource "aws_s3_bucket_server_side_encryption_configuration" "listing_images" {
  bucket = aws_s3_bucket.listing_images.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# ── 증빙 서류만 버전 관리 ────────────────────────────────────
#
# 실수로 지우거나 덮어써도 되돌린다. 심사 근거라 없어지면 곤란하다.
# 상품 이미지는 안 켠다 — 도매가 자주 갈아끼우는 파일이라 옛 버전이 계속 쌓인다
resource "aws_s3_bucket_versioning" "documents" {
  bucket = aws_s3_bucket.documents.id

  versioning_configuration {
    status = "Enabled"
  }
}

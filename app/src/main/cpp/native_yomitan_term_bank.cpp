#include <jni.h>

#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr jsize kReadBufferSize = 64 * 1024;
constexpr size_t kMaxRowBytes = 4 * 1024 * 1024;
constexpr size_t kMaxBatchBytes = 1024 * 1024;
constexpr size_t kMaxBatchRows = 256;

class StreamReader {
 public:
  StreamReader(JNIEnv* env, jobject stream)
      : env_(env), stream_(stream), buffer_(env->NewByteArray(kReadBufferSize)) {
    jclass stream_class = env_->GetObjectClass(stream_);
    read_method_ = env_->GetMethodID(stream_class, "read", "([B)I");
    env_->DeleteLocalRef(stream_class);
  }

  ~StreamReader() {
    if (buffer_ != nullptr) env_->DeleteLocalRef(buffer_);
  }

  int Peek() {
    if (position_ >= length_ && !Fill()) return -1;
    return bytes_[position_];
  }

  int Get() {
    const int value = Peek();
    if (value >= 0) ++position_;
    return value;
  }

 private:
  bool Fill() {
    if (eof_ || read_method_ == nullptr || buffer_ == nullptr) return false;
    position_ = 0;
    length_ = 0;
    int zero_reads = 0;
    for (;;) {
      const jint count = env_->CallIntMethod(stream_, read_method_, buffer_);
      if (env_->ExceptionCheck()) return false;
      if (count < 0) {
        eof_ = true;
        return false;
      }
      if (count == 0) {
        if (++zero_reads > 16) {
          jclass io_exception = env_->FindClass("java/io/IOException");
          if (io_exception != nullptr) {
            env_->ThrowNew(io_exception, "The ZIP entry stream returned no data repeatedly");
            env_->DeleteLocalRef(io_exception);
          }
          return false;
        }
        continue;
      }
      env_->GetByteArrayRegion(buffer_, 0, count, reinterpret_cast<jbyte*>(bytes_.data()));
      if (env_->ExceptionCheck()) return false;
      length_ = static_cast<size_t>(count);
      return true;
    }
  }

  JNIEnv* env_;
  jobject stream_;
  jbyteArray buffer_;
  jmethodID read_method_ = nullptr;
  std::array<uint8_t, kReadBufferSize> bytes_{};
  size_t position_ = 0;
  size_t length_ = 0;
  bool eof_ = false;
};

struct Scalar {
  enum class Type { kOther, kString, kNumber } type = Type::kOther;
  std::u16string string_value;
  std::string number_value;
};

struct TermRow {
  std::u16string expression;
  std::u16string reading;
  jint score = 0;
  std::string raw_json;
};

class TermBankParser {
 public:
  TermBankParser(JNIEnv* env, jobject consumer)
      : env_(env), consumer_(consumer) {
    jclass consumer_class = env_->GetObjectClass(consumer_);
    on_batch_ = env_->GetMethodID(
        consumer_class, "onBatch",
        "([Ljava/lang/String;[Ljava/lang/String;[I[Ljava/lang/String;)V");
    env_->DeleteLocalRef(consumer_class);
    string_class_ = env_->FindClass("java/lang/String");
  }

  ~TermBankParser() {
    if (string_class_ != nullptr) env_->DeleteLocalRef(string_class_);
  }

  bool Ready() const {
    return on_batch_ != nullptr && string_class_ != nullptr && !env_->ExceptionCheck();
  }

  bool Parse(StreamReader& input) {
    input_ = &input;
    if (!SkipWhitespace() || input_->Get() != '[') return false;
    if (!SkipWhitespace()) return false;
    if (input_->Peek() != ']') {
      for (;;) {
        if (input_->Peek() == '[') {
          TermRow row;
          bool accepted = false;
          if (!ParseTermRow(&row, &accepted)) return false;
          if (accepted && !AddRow(std::move(row))) return false;
        } else {
          if (!ParseValue(nullptr, nullptr)) return false;
        }
        if (!SkipWhitespace()) return false;
        const int delimiter = input_->Get();
        if (delimiter == ']') break;
        if (delimiter != ',' || !SkipWhitespace()) return false;
      }
    } else {
      input_->Get();
    }
    if (!SkipWhitespace() || input_->Peek() != -1) return false;
    return Flush();
  }

  jint ImportedCount() const { return imported_count_; }
  const std::string& Error() const { return error_; }

 private:
  bool Append(std::string* output, char value) {
    if (output == nullptr) return true;
    if (output->size() >= kMaxRowBytes) {
      error_ = "A Yomitan term row exceeds 4 MiB";
      return false;
    }
    output->push_back(value);
    return true;
  }

  bool Append(std::string* output, const std::string& value) {
    if (output == nullptr) return true;
    if (value.size() > kMaxRowBytes - output->size()) {
      error_ = "A Yomitan term row exceeds 4 MiB";
      return false;
    }
    output->append(value);
    return true;
  }

  bool SkipWhitespace() {
    for (;;) {
      const int value = input_->Peek();
      if (value == ' ' || value == '\t' || value == '\n' || value == '\r') {
        input_->Get();
      } else {
        return true;
      }
    }
  }

  static int HexValue(int value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
  }

  bool ParseString(std::string* output, std::u16string* decoded) {
    if (input_->Get() != '"' || !Append(output, '"')) return false;
    for (;;) {
      const int value = input_->Get();
      if (value < 0) return false;
      if (value == '"') return Append(output, '"');
      if (value < 0x20) return false;
      if (value == '\\') {
        if (!Append(output, '\\')) return false;
        const int escaped = input_->Get();
        if (escaped < 0 || !Append(output, static_cast<char>(escaped))) return false;
        switch (escaped) {
          case '"': if (decoded != nullptr) decoded->push_back(u'"'); break;
          case '\\': if (decoded != nullptr) decoded->push_back(u'\\'); break;
          case '/': if (decoded != nullptr) decoded->push_back(u'/'); break;
          case 'b': if (decoded != nullptr) decoded->push_back(u'\b'); break;
          case 'f': if (decoded != nullptr) decoded->push_back(u'\f'); break;
          case 'n': if (decoded != nullptr) decoded->push_back(u'\n'); break;
          case 'r': if (decoded != nullptr) decoded->push_back(u'\r'); break;
          case 't': if (decoded != nullptr) decoded->push_back(u'\t'); break;
          case 'u': {
            uint16_t code_unit = 0;
            for (int i = 0; i < 4; ++i) {
              const int hex = input_->Get();
              const int digit = HexValue(hex);
              if (digit < 0 || !Append(output, static_cast<char>(hex))) return false;
              code_unit = static_cast<uint16_t>((code_unit << 4) | digit);
            }
            if (decoded != nullptr) decoded->push_back(static_cast<char16_t>(code_unit));
            break;
          }
          default: return false;
        }
        continue;
      }

      if (value < 0x80) {
        if (!Append(output, static_cast<char>(value))) return false;
        if (decoded != nullptr) decoded->push_back(static_cast<char16_t>(value));
        continue;
      }

      uint32_t code_point = 0;
      int continuation_count = 0;
      if (value >= 0xc2 && value <= 0xdf) {
        code_point = static_cast<uint32_t>(value & 0x1f);
        continuation_count = 1;
      } else if (value >= 0xe0 && value <= 0xef) {
        code_point = static_cast<uint32_t>(value & 0x0f);
        continuation_count = 2;
      } else if (value >= 0xf0 && value <= 0xf4) {
        code_point = static_cast<uint32_t>(value & 0x07);
        continuation_count = 3;
      } else {
        return false;
      }
      if (!Append(output, static_cast<char>(value))) return false;
      for (int i = 0; i < continuation_count; ++i) {
        const int continuation = input_->Get();
        if (continuation < 0x80 || continuation > 0xbf ||
            !Append(output, static_cast<char>(continuation))) return false;
        code_point = (code_point << 6) | static_cast<uint32_t>(continuation & 0x3f);
      }
      if ((continuation_count == 2 && code_point < 0x800) ||
          (continuation_count == 3 && code_point < 0x10000) ||
          code_point > 0x10ffff || (code_point >= 0xd800 && code_point <= 0xdfff)) return false;
      if (decoded != nullptr) {
        if (code_point <= 0xffff) {
          decoded->push_back(static_cast<char16_t>(code_point));
        } else {
          code_point -= 0x10000;
          decoded->push_back(static_cast<char16_t>(0xd800 + (code_point >> 10)));
          decoded->push_back(static_cast<char16_t>(0xdc00 + (code_point & 0x3ff)));
        }
      }
    }
  }

  bool ParseNumber(std::string* output, Scalar* scalar) {
    std::string number;
    bool append_failed = false;
    auto take = [&]() {
      const int value = input_->Get();
      if (value < 0) {
        append_failed = true;
      } else if (scalar != nullptr) {
        if (number.size() >= kMaxRowBytes) {
          error_ = "A Yomitan term row exceeds 4 MiB";
          append_failed = true;
        } else {
          number.push_back(static_cast<char>(value));
        }
      } else if (!Append(output, static_cast<char>(value))) {
        append_failed = true;
      }
      return value;
    };
    auto digit = [](int value) { return value >= '0' && value <= '9'; };

    if (input_->Peek() == '-') {
      take();
      if (append_failed) return false;
    }
    int value = input_->Peek();
    if (value == '0') {
      take();
      if (digit(input_->Peek())) return false;
    } else if (value >= '1' && value <= '9') {
      do {
        take();
        if (append_failed) return false;
        value = input_->Peek();
      } while (digit(value));
    } else {
      return false;
    }
    if (append_failed) return false;
    if (input_->Peek() == '.') {
      take();
      if (!digit(input_->Peek())) return false;
      while (digit(input_->Peek())) {
        take();
        if (append_failed) return false;
      }
    }
    value = input_->Peek();
    if (value == 'e' || value == 'E') {
      take();
      value = input_->Peek();
      if (value == '+' || value == '-') take();
      if (!digit(input_->Peek())) return false;
      while (digit(input_->Peek())) {
        take();
        if (append_failed) return false;
      }
    }
    if (append_failed) return false;
    if (scalar != nullptr) {
      scalar->type = Scalar::Type::kNumber;
      scalar->number_value = number;
      return Append(output, number);
    }
    return true;
  }

  bool ParseValue(std::string* output, Scalar* scalar) {
    if (scalar != nullptr) *scalar = Scalar{};
    if (!SkipWhitespace()) return false;
    const int value = input_->Peek();
    if (value == '"') {
      std::u16string decoded;
      if (!ParseString(output, scalar == nullptr ? nullptr : &decoded)) return false;
      if (scalar != nullptr) {
        scalar->type = Scalar::Type::kString;
        scalar->string_value = std::move(decoded);
      }
      return true;
    }
    if (value == '[') {
      input_->Get();
      if (!Append(output, '[') || !SkipWhitespace()) return false;
      if (input_->Peek() == ']') {
        input_->Get();
        return Append(output, ']');
      }
      for (;;) {
        if (!ParseValue(output, nullptr) || !SkipWhitespace()) return false;
        const int delimiter = input_->Get();
        if (delimiter == ']') return Append(output, ']');
        if (delimiter != ',' || !Append(output, ',') || !SkipWhitespace()) return false;
      }
    }
    if (value == '{') {
      input_->Get();
      if (!Append(output, '{') || !SkipWhitespace()) return false;
      if (input_->Peek() == '}') {
        input_->Get();
        return Append(output, '}');
      }
      for (;;) {
        if (input_->Peek() != '"' || !ParseString(output, nullptr) ||
            !SkipWhitespace() || input_->Get() != ':' || !Append(output, ':') ||
            !ParseValue(output, nullptr) || !SkipWhitespace()) return false;
        const int delimiter = input_->Get();
        if (delimiter == '}') return Append(output, '}');
        if (delimiter != ',' || !Append(output, ',') || !SkipWhitespace()) return false;
      }
    }
    if (value == 't') return ParseLiteral(output, "true");
    if (value == 'f') return ParseLiteral(output, "false");
    if (value == 'n') return ParseLiteral(output, "null");
    return ParseNumber(output, scalar);
  }

  bool ParseLiteral(std::string* output, const char* literal) {
    for (const char* character = literal; *character != '\0'; ++character) {
      if (input_->Get() != *character || !Append(output, *character)) return false;
    }
    return true;
  }

  bool ParseTermRow(TermRow* row, bool* accepted) {
    *accepted = false;
    if (input_->Get() != '[' || !Append(&row->raw_json, '[') || !SkipWhitespace()) return false;
    size_t field_index = 0;
    bool has_expression = false;
    if (input_->Peek() != ']') {
      for (;;) {
        if (field_index > 0 && !Append(&row->raw_json, ',')) return false;
        Scalar scalar;
        Scalar* capture = (field_index == 0 || field_index == 1 || field_index == 4) ? &scalar : nullptr;
        if (!ParseValue(&row->raw_json, capture)) return false;
        if (field_index == 0 && scalar.type == Scalar::Type::kString) {
          row->expression = std::move(scalar.string_value);
          has_expression = true;
        } else if (field_index == 1 && scalar.type == Scalar::Type::kString) {
          row->reading = std::move(scalar.string_value);
        } else if (field_index == 4 && scalar.type == Scalar::Type::kNumber) {
          row->score = ParseScore(scalar.number_value);
        }
        ++field_index;
        if (!SkipWhitespace()) return false;
        const int delimiter = input_->Get();
        if (delimiter == ']') break;
        if (delimiter != ',' || !SkipWhitespace()) return false;
      }
    } else {
      input_->Get();
    }
    if (!Append(&row->raw_json, ']')) return false;
    *accepted = field_index >= 7 && has_expression && !row->expression.empty();
    return true;
  }

  static jint ParseScore(const std::string& number) {
    if (number.find_first_of(".eE") == std::string::npos) {
      errno = 0;
      char* end = nullptr;
      const long long value = std::strtoll(number.c_str(), &end, 10);
      if (errno != ERANGE && end != number.c_str() && *end == '\0') {
        const uint32_t low_bits = static_cast<uint32_t>(value);
        jint result;
        std::memcpy(&result, &low_bits, sizeof(result));
        return result;
      }
    }
    errno = 0;
    char* end = nullptr;
    const double value = std::strtod(number.c_str(), &end);
    if (end == number.c_str() || *end != '\0' || std::isnan(value)) return 0;
    if (value >= static_cast<double>(std::numeric_limits<jint>::max())) return std::numeric_limits<jint>::max();
    if (value <= static_cast<double>(std::numeric_limits<jint>::min())) return std::numeric_limits<jint>::min();
    return static_cast<jint>(value);
  }

  bool AddRow(TermRow row) {
    const size_t bytes = row.raw_json.size();
    if (!batch_.empty() &&
        (batch_.size() >= kMaxBatchRows || bytes > kMaxBatchBytes - batch_bytes_)) {
      if (!Flush()) return false;
    }
    batch_bytes_ += bytes;
    batch_.push_back(std::move(row));
    if (batch_.size() >= kMaxBatchRows || batch_bytes_ >= kMaxBatchBytes) return Flush();
    return true;
  }

  jstring NewJavaString(const std::u16string& value) {
    return env_->NewString(reinterpret_cast<const jchar*>(value.data()),
                           static_cast<jsize>(value.size()));
  }

  static bool AppendUtf8AsUtf16(const std::string& value, std::u16string* output) {
    size_t index = 0;
    while (index < value.size()) {
      const uint8_t first = static_cast<uint8_t>(value[index++]);
      uint32_t code_point = 0;
      int continuation_count = 0;
      if (first <= 0x7f) {
        code_point = first;
      } else if (first >= 0xc2 && first <= 0xdf) {
        code_point = first & 0x1f;
        continuation_count = 1;
      } else if (first >= 0xe0 && first <= 0xef) {
        code_point = first & 0x0f;
        continuation_count = 2;
      } else if (first >= 0xf0 && first <= 0xf4) {
        code_point = first & 0x07;
        continuation_count = 3;
      } else {
        return false;
      }
      for (int i = 0; i < continuation_count; ++i) {
        if (index >= value.size()) return false;
        const uint8_t continuation = static_cast<uint8_t>(value[index++]);
        if (continuation < 0x80 || continuation > 0xbf) return false;
        code_point = (code_point << 6) | (continuation & 0x3f);
      }
      if ((continuation_count == 2 && code_point < 0x800) ||
          (continuation_count == 3 && code_point < 0x10000) ||
          code_point > 0x10ffff || (code_point >= 0xd800 && code_point <= 0xdfff)) return false;
      if (code_point <= 0xffff) {
        output->push_back(static_cast<char16_t>(code_point));
      } else {
        code_point -= 0x10000;
        output->push_back(static_cast<char16_t>(0xd800 + (code_point >> 10)));
        output->push_back(static_cast<char16_t>(0xdc00 + (code_point & 0x3ff)));
      }
    }
    return true;
  }

  bool Flush() {
    if (batch_.empty()) return true;
    const jsize count = static_cast<jsize>(batch_.size());
    jobjectArray expressions = env_->NewObjectArray(count, string_class_, nullptr);
    jobjectArray readings = env_->NewObjectArray(count, string_class_, nullptr);
    jobjectArray raw_rows = env_->NewObjectArray(count, string_class_, nullptr);
    jintArray scores = env_->NewIntArray(count);
    if (expressions == nullptr || readings == nullptr || raw_rows == nullptr || scores == nullptr) {
      return false;
    }
    std::vector<jint> score_values;
    score_values.reserve(batch_.size());
    for (jsize i = 0; i < count; ++i) {
      const TermRow& row = batch_[static_cast<size_t>(i)];
      jstring expression = NewJavaString(row.expression);
      jstring reading = NewJavaString(row.reading);
      std::u16string raw_utf16;
      if (!AppendUtf8AsUtf16(row.raw_json, &raw_utf16)) {
        if (expression != nullptr) env_->DeleteLocalRef(expression);
        if (reading != nullptr) env_->DeleteLocalRef(reading);
        return false;
      }
      jstring raw = NewJavaString(raw_utf16);
      if (expression == nullptr || reading == nullptr || raw == nullptr) {
        if (expression != nullptr) env_->DeleteLocalRef(expression);
        if (reading != nullptr) env_->DeleteLocalRef(reading);
        if (raw != nullptr) env_->DeleteLocalRef(raw);
        return false;
      }
      env_->SetObjectArrayElement(expressions, i, expression);
      env_->SetObjectArrayElement(readings, i, reading);
      env_->SetObjectArrayElement(raw_rows, i, raw);
      env_->DeleteLocalRef(expression);
      env_->DeleteLocalRef(reading);
      env_->DeleteLocalRef(raw);
      if (env_->ExceptionCheck()) return false;
      score_values.push_back(row.score);
    }
    env_->SetIntArrayRegion(scores, 0, count, score_values.data());
    if (!env_->ExceptionCheck()) {
      env_->CallVoidMethod(consumer_, on_batch_, expressions, readings, scores, raw_rows);
    }
    const bool success = !env_->ExceptionCheck();
    env_->DeleteLocalRef(expressions);
    env_->DeleteLocalRef(readings);
    env_->DeleteLocalRef(scores);
    env_->DeleteLocalRef(raw_rows);
    if (!success) return false;
    imported_count_ += count;
    batch_.clear();
    batch_bytes_ = 0;
    return true;
  }

  JNIEnv* env_;
  jobject consumer_;
  jmethodID on_batch_ = nullptr;
  jclass string_class_ = nullptr;
  StreamReader* input_ = nullptr;
  std::vector<TermRow> batch_;
  size_t batch_bytes_ = 0;
  jint imported_count_ = 0;
  std::string error_;
};

void ThrowMalformedJson(JNIEnv* env, const std::string& message) {
  if (env->ExceptionCheck()) return;
  jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
  if (exception_class == nullptr) return;
  const char* message_text = message.empty() ? "Malformed Yomitan term bank JSON" : message.c_str();
  env->ThrowNew(exception_class, message_text);
  env->DeleteLocalRef(exception_class);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_nekoplayer_app_data_NativeYomitanTermBank_parseTermBank(
    JNIEnv* env, jobject /* instance */, jobject input, jobject consumer) {
  if (input == nullptr || consumer == nullptr) {
    ThrowMalformedJson(env, "The Yomitan term bank stream is unavailable");
    return -1;
  }
  StreamReader reader(env, input);
  TermBankParser parser(env, consumer);
  if (!parser.Ready()) return -1;
  if (!parser.Parse(reader)) {
    if (!env->ExceptionCheck()) ThrowMalformedJson(env, parser.Error());
    return -1;
  }
  return parser.ImportedCount();
}

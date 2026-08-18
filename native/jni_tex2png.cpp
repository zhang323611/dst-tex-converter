#include <jni.h>
#include <vector>
#include <string>

int tex2png_main(int argc, char** argv);

extern "C" JNIEXPORT jint JNICALL
Java_com_dsttex_TexConverter_nativeTex2png(JNIEnv* env, jobject thiz, jobjectArray args) {
    int n = env->GetArrayLength(args);
    // argv[0] 必须是程序名（tex2png_main 从 argv[1] 开始取输入/输出路径）
    std::vector<std::string> storage(n + 1);
    std::vector<char*> argv(n + 1);
    storage[0] = "tex2png";
    argv[0] = const_cast<char*>(storage[0].c_str());
    for (int i = 0; i < n; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(args, i);
        const char* c = env->GetStringUTFChars(js, nullptr);
        storage[i + 1] = c ? c : "";
        argv[i + 1] = const_cast<char*>(storage[i + 1].c_str());
        if (c) env->ReleaseStringUTFChars(js, c);
        env->DeleteLocalRef(js);
    }
    return tex2png_main(n + 1, argv.data());
}

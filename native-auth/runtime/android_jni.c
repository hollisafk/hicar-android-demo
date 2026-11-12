#define _DARWIN_C_SOURCE 1
#define _GNU_SOURCE 1
/* HiCarLab JNI adapter; based on CarLink hichain.py (MIT).
 * Native dependencies retain their own licenses. No PIN or key is logged. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <errno.h>
#include <openssl/sha.h>
#include "hichain.h"
#include "hks_file_api.h"
#include "hks_hardware_api.h"

static int root_fd=-1;
static uint8_t hardware_id[32];
static int valid_name(const char *s) {return s&&(!strcmp(s,"info1.data")||!strcmp(s,"info2.data")||!strcmp(s,"hks_keystore"));}
static int32_t read_file(const char *n,uint32_t off,uint8_t *b,uint32_t len){
 if(!valid_name(n)||off>1048576||len>1048576-off)return -1;
 int fd=openat(root_fd,n,O_RDONLY|O_NOFOLLOW);if(fd<0)return errno==ENOENT?0:-1;
 ssize_t r=pread(fd,b,len,off);close(fd);return (int32_t)r;
}
static int32_t write_file(const char *n,uint32_t off,const uint8_t *b,uint32_t len){
 if(!valid_name(n)||off>1048576||len>1048576-off)return -1;
 int fd=openat(root_fd,n,O_WRONLY|O_CREAT|O_NOFOLLOW,0600);if(fd<0)return -1;
 ssize_t r=pwrite(fd,b,len,off);int ok=r==(ssize_t)len&&fsync(fd)==0;close(fd);return ok?0:-1;
}
static int32_t file_size(const char *n){
 if(!valid_name(n))return -1;struct stat s;
 int fd=openat(root_fd,n,O_RDONLY|O_NOFOLLOW);if(fd<0)return errno==ENOENT?0:-1;
 int ok=fstat(fd,&s)==0&&S_ISREG(s.st_mode)&&s.st_size<=1048576;close(fd);return ok?(int32_t)s.st_size:-1;
}
static int32_t udid(uint8_t *out,uint32_t len){if(len<32)return -1;memcpy(out,hardware_id,32);return 0;}
static struct hks_file_callbacks files={read_file,write_file,file_size};
typedef struct {
 hc_handle handle; struct operation_parameter params;struct hc_pin pin;
 struct session_identity identity;struct hc_call_back callbacks;
 uint8_t *packets[8];uint32_t lengths[8];int count,error,result;
 struct hc_session_key key;
} Session;
static Session *ctx(const struct session_identity *i){return i?(Session*)i->context:NULL;}
static void transmit(const struct session_identity *i,const void *data,uint32_t len){
 Session*s=ctx(i);if(!s)return;
 if(!len||len>65536||s->count>=8||memchr(data,0,len)){s->error=1;return;}
 uint8_t*b=malloc(len+1);if(!b){s->error=1;return;}memcpy(b,data,len);b[len]=0;s->packets[s->count]=b;s->lengths[s->count++]=len;
}
static void params(const struct session_identity*i,int32_t op,struct hc_pin*p,struct operation_parameter*v){
 Session*s=ctx(i);if(!s)return;if(op!=0&&op!=1&&op!=2&&op!=99){s->error=1;return;}*p=s->pin;*v=s->params;
}
static void set_key(const struct session_identity*i,const struct hc_session_key*k){Session*s=ctx(i);if(s&&k&&k->length>0&&k->length<=128)s->key=*k;else if(s)s->error=1;}
static void result(const struct session_identity*i,int32_t r){Session*s=ctx(i);if(s)s->result=r;}
static int32_t confirm(const struct session_identity*i,int32_t op){(void)i;return op==1||op==2?0:-1;}
static int copy_string(JNIEnv*e,jstring str,uint8_t*out,int max){
 if(!str)return 0;const char*p=(*e)->GetStringUTFChars(e,str,NULL);if(!p)return 0;
 int n=(int)strlen(p);if(n>0&&n<=max)memcpy(out,p,n);else n=0;(*e)->ReleaseStringUTFChars(e,str,p);return n;
}
JNIEXPORT jint JNICALL Java_local_hicarlab_NativeAuth_init(JNIEnv*e,jclass c,jstring path,jstring seed){
 (void)c;if(root_fd>=0)return 0;uint8_t buf[256];int n=copy_string(e,seed,buf,sizeof(buf));if(!n)return -1;
 SHA256(buf,n,hardware_id);memset(buf,0,sizeof(buf));
 const char*p=(*e)->GetStringUTFChars(e,path,NULL);if(!p)return -1;
 root_fd=open(p,O_RDONLY|O_DIRECTORY|O_NOFOLLOW);(*e)->ReleaseStringUTFChars(e,path,p);if(root_fd<0)return -2;
 struct stat s;if(fstat(root_fd,&s)||s.st_mode&0077){close(root_fd);root_fd=-1;return -3;}
 int r=hks_register_file_callbacks(&files);if(!r)r=hks_register_get_hardware_udid_callback(udid);if(r){close(root_fd);root_fd=-1;}return r;
}
JNIEXPORT jlong JNICALL Java_local_hicarlab_NativeAuth_create(JNIEnv*e,jclass c,jstring self,jstring peer,jstring pin,jint role){
 (void)c;if(root_fd<0||(role!=1&&role!=2))return 0;Session*s=calloc(1,sizeof(*s));if(!s)return 0;s->result=-1;
 s->params.self_auth_id.length=copy_string(e,self,s->params.self_auth_id.auth_id,64);
 s->params.peer_auth_id.length=copy_string(e,peer,s->params.peer_auth_id.auth_id,64);
 s->pin.length=copy_string(e,pin,s->pin.pin,16);s->params.key_length=32;
 if(!s->params.self_auth_id.length||!s->params.peer_auth_id.length||s->pin.length!=6){free(s);return 0;}
 for(int j=0;j<6;j++)if(s->pin.pin[j]<'0'||s->pin.pin[j]>'9'){free(s);return 0;}
 s->identity.package_name.length=16;memcpy(s->identity.package_name.name,"com.huawei.hicar",16);
 s->identity.service_type.length=9;memcpy(s->identity.service_type.type,"CarDevice",9);
 s->identity.context=s;s->callbacks=(struct hc_call_back){transmit,params,set_key,result,confirm};
 s->handle=get_instance(&s->identity,role,&s->callbacks);if(!s->handle){memset(s,0,sizeof(*s));free(s);return 0;}return (jlong)(intptr_t)s;
}
JNIEXPORT jobjectArray JNICALL Java_local_hicarlab_NativeAuth_step(JNIEnv*e,jclass c,jlong handle,jbyteArray data,jint key_length){
 (void)c;Session*s=(Session*)(intptr_t)handle;if(!s)return NULL;
 if(key_length!=16&&key_length!=32)return NULL;s->params.key_length=key_length;
 int code;
 if(!data)code=start_pake(s->handle,&s->params);
 else {
  jsize n=(*e)->GetArrayLength(e,data);if(n<=0||n>65536)return NULL;
  uint8_t*b=malloc(n+1);if(!b)return NULL;(*e)->GetByteArrayRegion(e,data,0,n,(jbyte*)b);b[n]=0;
  struct uint8_buff v={b,(uint32_t)n,(uint32_t)n};code=receive_data(s->handle,&v);memset(b,0,n);free(b);
 }
 jclass bytes=(*e)->FindClass(e,"[B");jobjectArray out=(*e)->NewObjectArray(e,s->count+1,bytes,NULL);
 int32_t meta[4]={code,s->result,(int32_t)s->key.length,s->error};
 jbyteArray m=(*e)->NewByteArray(e,sizeof(meta));(*e)->SetByteArrayRegion(e,m,0,sizeof(meta),(jbyte*)meta);(*e)->SetObjectArrayElement(e,out,0,m);(*e)->DeleteLocalRef(e,m);
 for(int j=0;j<s->count;j++){jbyteArray b=(*e)->NewByteArray(e,s->lengths[j]);(*e)->SetByteArrayRegion(e,b,0,s->lengths[j],(jbyte*)s->packets[j]);(*e)->SetObjectArrayElement(e,out,j+1,b);(*e)->DeleteLocalRef(e,b);memset(s->packets[j],0,s->lengths[j]);free(s->packets[j]);}
 s->count=0;return out;
}
/* Copy only a completed session's own key into private process memory. */
JNIEXPORT jbyteArray JNICALL Java_local_hicarlab_NativeAuth_sessionKey(JNIEnv*e,jclass c,jlong handle){
 (void)c;Session*s=(Session*)(intptr_t)handle;
 if(!s||s->error||s->result!=0||(s->key.length!=16&&s->key.length!=32))return NULL;
 jbyteArray out=(*e)->NewByteArray(e,(jsize)s->key.length);
 if(out)(*e)->SetByteArrayRegion(e,out,0,(jsize)s->key.length,(const jbyte*)s->key.session_key);
 return out;
}
JNIEXPORT void JNICALL Java_local_hicarlab_NativeAuth_close(JNIEnv*e,jclass c,jlong handle){
 (void)e;(void)c;Session*s=(Session*)(intptr_t)handle;if(!s)return;if(s->handle)destroy(&s->handle);
 for(int j=0;j<s->count;j++){memset(s->packets[j],0,s->lengths[j]);free(s->packets[j]);}memset(s,0,sizeof(*s));free(s);
}

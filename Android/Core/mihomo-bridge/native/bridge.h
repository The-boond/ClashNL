#include <stdint.h>

void coreInit(char* home, int sdkVersion);
char* validateConfig(char* content, char* controller, char* secret);
char* describeProxyGroups(char* content);
char* loadConfig(char* content, char* controller, char* secret, int httpProxyPort);
char* setMode(char* mode);
void prepareTun(void* callback);
char* startTun(int fd, char* stack, char* gateway, char* dns);
void stopTun(void);
void stopCore(void);

int protect_socket(void* callback, int fd);
int query_socket_uid(void* callback, int protocol, char* source, char* target);
void release_callback(void* callback);

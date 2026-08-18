#include <stdint.h>

void coreInit(char* home, int sdkVersion);
char* validateConfig(char* content, char* controller, char* secret);
char* loadConfig(char* content, char* controller, char* secret);
char* startTun(int fd, char* stack, char* gateway, char* dns, void* callback);
void stopTun(void);
void stopCore(void);

int protect_socket(void* callback, int fd);
int query_socket_uid(void* callback, int protocol, char* source, char* target);
void release_callback(void* callback);

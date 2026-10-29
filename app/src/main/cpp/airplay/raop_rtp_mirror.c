//
// Created by Administrator on 2019/1/29/029.
//

#include "raop_rtp_mirror.h"

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>
#include <errno.h>

#include "raop.h"
#include "netutils.h"
#include "compat.h"
#include "logger.h"
#include "byteutils.h"
#include "mirror_buffer.h"
#include "stream.h"


struct h264codec_s {
    unsigned char compatibility;
    short lengthofPPS;
    short lengthofSPS;
    unsigned char level;
    unsigned char numberOfPPS;
    unsigned char* picture_parameter_set;
    unsigned char profile_high;
    unsigned char reserved3andSPS;
    unsigned char reserved6andNAL;
    unsigned char* sequence;
    unsigned char version;
};

struct raop_rtp_mirror_s {
    logger_t *logger;
    raop_callbacks_t callbacks;

    /* Buffer to handle all resends */
    mirror_buffer_t *buffer;

    raop_rtp_mirror_t *mirror;
    /* Remote address as sockaddr */
    struct sockaddr_storage remote_saddr;
    socklen_t remote_saddr_len;

    /* MUTEX LOCKED VARIABLES START */
    /* These variables only edited mutex locked */
    int running;
    int joined;

    int flush;
    thread_handle_t thread_mirror;
    thread_handle_t thread_time;
    mutex_handle_t run_mutex;

    mutex_handle_t time_mutex;
    cond_handle_t time_cond;
    /* MUTEX LOCKED VARIABLES END */
    int mirror_data_sock, mirror_time_sock;

    unsigned short mirror_data_lport;
    unsigned short mirror_timing_rport;
    unsigned short mirror_timing_lport;
};

static int
raop_rtp_parse_remote(raop_rtp_mirror_t *raop_rtp_mirror, const unsigned char *remote, int remotelen)
{
    char current[25];
    int family;
    int ret;
    assert(raop_rtp_mirror);
    if (remotelen == 4) {
        family = AF_INET;
    } else if (remotelen == 16) {
        family = AF_INET6;
    } else {
        return -1;
    }
    memset(current, 0, sizeof(current));
    sprintf(current, "%d.%d.%d.%d", remote[0], remote[1], remote[2], remote[3]);
    logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_parse_remote ip = %s", current);
    ret = netutils_parse_address(family, current,
                                 &raop_rtp_mirror->remote_saddr,
                                 sizeof(raop_rtp_mirror->remote_saddr));
    if (ret < 0) {
        return -1;
    }
    raop_rtp_mirror->remote_saddr_len = ret;
    return 0;
}

#define NO_FLUSH (-42)
raop_rtp_mirror_t *raop_rtp_mirror_init(logger_t *logger, raop_callbacks_t *callbacks, const unsigned char *remote, int remotelen,
                                        const unsigned char *aeskey, const unsigned char *ecdh_secret, unsigned short timing_rport)
{
    raop_rtp_mirror_t *raop_rtp_mirror;

    assert(logger);
    assert(callbacks);

    raop_rtp_mirror = calloc(1, sizeof(raop_rtp_mirror_t));
    if (!raop_rtp_mirror) {
        return NULL;
    }
    raop_rtp_mirror->logger = logger;
    raop_rtp_mirror->mirror_timing_rport = timing_rport;

    memcpy(&raop_rtp_mirror->callbacks, callbacks, sizeof(raop_callbacks_t));
    raop_rtp_mirror->buffer = mirror_buffer_init(logger, aeskey, ecdh_secret);
    if (!raop_rtp_mirror->buffer) {
        free(raop_rtp_mirror);
        return NULL;
    }
    if (raop_rtp_parse_remote(raop_rtp_mirror, remote, remotelen) < 0) {
        free(raop_rtp_mirror);
        return NULL;
    }
    raop_rtp_mirror->running = 0;
    raop_rtp_mirror->joined = 1;
    raop_rtp_mirror->flush = NO_FLUSH;

    MUTEX_CREATE(raop_rtp_mirror->run_mutex);
    MUTEX_CREATE(raop_rtp_mirror->time_mutex);
    COND_CREATE(raop_rtp_mirror->time_cond);
    return raop_rtp_mirror;
}

void
raop_rtp_init_mirror_aes(raop_rtp_mirror_t *raop_rtp_mirror, uint64_t streamConnectionID)
{
    mirror_buffer_init_aes(raop_rtp_mirror->buffer, streamConnectionID);
}

/**
 * ntp
 */
static THREAD_RETVAL
raop_rtp_mirror_thread_time(void *arg)
{
    raop_rtp_mirror_t *raop_rtp_mirror = arg;
    assert(raop_rtp_mirror);
    struct sockaddr_storage saddr;
    socklen_t saddrlen;
    unsigned char packet[128];
    unsigned int packetlen;
    int first = 0;
    unsigned char time[48]={35,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
    uint64_t base = now_us();
    uint64_t rec_pts = 0;
    while (1) {
        MUTEX_LOCK(raop_rtp_mirror->run_mutex);
        if (!raop_rtp_mirror->running) {
            MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
            break;
        }
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        uint64_t send_time = now_us() - base + rec_pts;

        byteutils_put_timeStamp(time, 40, send_time);
        logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_mirror_thread_time send time 48 bytes, port = %d", raop_rtp_mirror->mirror_timing_rport);
        struct sockaddr_in *addr = (struct sockaddr_in *)&raop_rtp_mirror->remote_saddr;
        addr->sin_port = htons(raop_rtp_mirror->mirror_timing_rport);
        int sendlen = sendto(raop_rtp_mirror->mirror_time_sock, (char *)time, sizeof(time), 0, (struct sockaddr *) &raop_rtp_mirror->remote_saddr, raop_rtp_mirror->remote_saddr_len);
        logger_log(raop_rtp_mirror->logger, LOGGER_DEBUG, "raop_rtp_mirror_thread_time sendlen = %d", sendlen);

        saddrlen = sizeof(saddr);
        packetlen = recvfrom(raop_rtp_mirror->mirror_time_sock, (char *)packet, sizeof(packet), 0,
                             (struct sockaddr *)&saddr, &saddrlen);
        if ((int) packetlen < 48) {
            continue;
        }
        // 16-24 系统时钟最后一次被设定或更新的时间。
        uint64_t Reference_Timestamp = byteutils_read_timeStamp(packet, 16);
        // 24-32 NTP请求报文离开发送端时发送端的本地时间。  T1
        uint64_t Origin_Timestamp = byteutils_read_timeStamp(packet, 24);
        // 32-40 NTP请求报文到达接收端时接收端的本地时间。 T2
        uint64_t Receive_Timestamp = byteutils_read_timeStamp(packet, 32);
        // 40-48 Transmit Timestamp：应答报文离开应答者时应答者的本地时间。 T3
        uint64_t Transmit_Timestamp = byteutils_read_timeStamp(packet, 40);

        // FIXME: 先简单这样写吧
        rec_pts = Receive_Timestamp;

        if (first == 0) {
            first++;
        } else {
            struct timeval now;
            struct timespec outtime;
            MUTEX_LOCK(raop_rtp_mirror->time_mutex);
            gettimeofday(&now, NULL);
            outtime.tv_sec = now.tv_sec + 3;
            outtime.tv_nsec = now.tv_usec * 1000;
            int ret = pthread_cond_timedwait(&raop_rtp_mirror->time_cond, &raop_rtp_mirror->time_mutex, &outtime);
            MUTEX_UNLOCK(raop_rtp_mirror->time_mutex);
            //sleepms(3000);
        }
    }
    logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Exiting UDP raop_rtp_mirror_thread_time thread");
    return 0;
}
//#define DUMP_H264

#define RAOP_PACKET_LEN 32768

/* Reads exactly len bytes; -1 when the peer closes the socket or it fails. */
static int
recv_all(int fd, unsigned char *buf, int len)
{
    int got = 0;
    while (got < len) {
        int ret = recv(fd, buf + got, len - got, 0);
        if (ret <= 0) {
            if (ret < 0 && errno == EINTR) continue;
            return -1;
        }
        got += ret;
    }
    return 0;
}
/**
 * 镜像
 */
static THREAD_RETVAL
raop_rtp_mirror_thread(void *arg)
{
    raop_rtp_mirror_t *raop_rtp_mirror = arg;
    int stream_fd = -1;
    unsigned char packet[128];
    memset(packet, 0 , 128);
    uint64_t pts_base = 0;
    uint64_t pts = 0;
    assert(raop_rtp_mirror);

#ifdef DUMP_H264
    // C 解密的
    FILE* file = fopen("/sdcard/111.h264", "wb");
    // 加密的源文件
    FILE* file_source = fopen("/sdcard/111.source", "wb");

    FILE* file_len = fopen("/sdcard/111.len", "wb");
#endif
    while (1) {
        fd_set rfds;
        struct timeval tv;
        int nfds, ret;
        MUTEX_LOCK(raop_rtp_mirror->run_mutex);
        if (!raop_rtp_mirror->running) {
            MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
            break;
        }
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        /* Set timeout value to 5ms */
        tv.tv_sec = 0;
        tv.tv_usec = 5000;

        /* Get the correct nfds value and set rfds */
        FD_ZERO(&rfds);
        if (stream_fd == -1) {
            FD_SET(raop_rtp_mirror->mirror_data_sock, &rfds);
            nfds = raop_rtp_mirror->mirror_data_sock+1;
        } else {
            FD_SET(stream_fd, &rfds);
            nfds = stream_fd+1;
        }
        ret = select(nfds, &rfds, NULL, NULL, &tv);
        if (ret == 0) {
            /* Timeout happened */
            continue;
        } else if (ret == -1) {
            /* FIXME: Error happened */
            logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Error in select");
            break;
        }
        if (stream_fd == -1 && FD_ISSET(raop_rtp_mirror->mirror_data_sock, &rfds)) {
            struct sockaddr_storage saddr;
            socklen_t saddrlen;

            logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Accepting client");
            saddrlen = sizeof(saddr);
            stream_fd = accept(raop_rtp_mirror->mirror_data_sock, (struct sockaddr *)&saddr, &saddrlen);
            if (stream_fd == -1) {
                /* FIXME: Error happened */
                logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Error in accept %d %s", errno, strerror(errno));
                break;
            }
        }
        if (stream_fd != -1 && FD_ISSET(stream_fd, &rfds)) {
            /* Every packet starts with a 128-byte header: payload size, type and NTP time. */
            if (recv_all(stream_fd, packet, 128) < 0) {
                logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Mirror TCP socket closed");
                break;
            }
            int payloadsize = byteutils_get_int(packet, 0);
            short payloadtype = (short) (byteutils_get_short(packet, 4) & 0xff);
            if (payloadsize < 0 || payloadsize > 8 * 1024 * 1024) {
                logger_log(raop_rtp_mirror->logger, LOGGER_ERR, "Bad mirror payload size %d", payloadsize);
                break;
            }
            unsigned char *payload_in = payloadsize > 0 ? malloc(payloadsize) : NULL;
            if (payloadsize > 0 && (!payload_in || recv_all(stream_fd, payload_in, payloadsize) < 0)) {
                free(payload_in);
                logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Mirror TCP socket closed");
                break;
            }

            if (payloadtype == 0 && payloadsize > 0) {
                /* Encrypted video frame: AVCC length-prefixed NAL units. */
                uint64_t payloadntp = byteutils_get_long(packet, 8);
                if (pts_base == 0) {
                    pts_base = ntptopts(payloadntp);
                } else {
                    pts = ntptopts(payloadntp) - pts_base;
                }
                unsigned char *payload = malloc(payloadsize);
                mirror_buffer_decrypt(raop_rtp_mirror->buffer, payload_in, payload, payloadsize);
                /* Turn the 4-byte lengths into Annex-B start codes for MediaCodec. */
                int offset = 0;
                int valid = 1;
                while (offset + 4 <= payloadsize) {
                    int nc_len = (payload[offset] << 24) | (payload[offset + 1] << 16) |
                                 (payload[offset + 2] << 8) | payload[offset + 3];
                    if (nc_len <= 0 || offset + 4 + nc_len > payloadsize) {
                        valid = 0;
                        break;
                    }
                    payload[offset] = 0;
                    payload[offset + 1] = 0;
                    payload[offset + 2] = 0;
                    payload[offset + 3] = 1;
                    offset += nc_len + 4;
                }
                if (valid) {
                    h264_decode_struct h264_data;
                    h264_data.data_len = payloadsize;
                    h264_data.data = payload;
                    h264_data.frame_type = 1;
                    h264_data.pts = pts;
                    raop_rtp_mirror->callbacks.video_process(raop_rtp_mirror->callbacks.cls, &h264_data);
                } else {
                    logger_log(raop_rtp_mirror->logger, LOGGER_WARNING, "Dropped malformed video frame");
                }
                free(payload);
            } else if (payloadtype == 1 && payloadsize > 11) {
                /* Unencrypted avcC record with SPS and PPS; sent at start and on rotation. */
                unsigned char *payload = payload_in;
                int sps_len = (payload[6] << 8) | payload[7];
                int pps_off = 8 + sps_len + 1;
                int pps_len = pps_off + 2 <= payloadsize ? (payload[pps_off] << 8) | payload[pps_off + 1] : -1;
                if (sps_len > 0 && pps_len > 0 && pps_off + 2 + pps_len <= payloadsize) {
                    int sps_pps_len = sps_len + pps_len + 8;
                    unsigned char *sps_pps = malloc(sps_pps_len);
                    sps_pps[0] = 0; sps_pps[1] = 0; sps_pps[2] = 0; sps_pps[3] = 1;
                    memcpy(sps_pps + 4, payload + 8, sps_len);
                    sps_pps[sps_len + 4] = 0; sps_pps[sps_len + 5] = 0;
                    sps_pps[sps_len + 6] = 0; sps_pps[sps_len + 7] = 1;
                    memcpy(sps_pps + sps_len + 8, payload + pps_off + 2, pps_len);
                    logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Mirror codec config: %.0fx%.0f",
                               byteutils_get_float(packet, 56), byteutils_get_float(packet, 60));
                    h264_decode_struct h264_data;
                    h264_data.data_len = sps_pps_len;
                    h264_data.data = sps_pps;
                    h264_data.frame_type = 0;
                    h264_data.pts = 0;
                    raop_rtp_mirror->callbacks.video_process(raop_rtp_mirror->callbacks.cls, &h264_data);
                    free(sps_pps);
                } else {
                    logger_log(raop_rtp_mirror->logger, LOGGER_WARNING, "Bad SPS/PPS record");
                }
            }
            /* Types 2 (heartbeat) and 4 (stats) carry nothing we need. */
            free(payload_in);
        }
    }

    /* Close the stream file descriptor */
    if (stream_fd != -1) {
        closesocket(stream_fd);
    }
    logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Exiting TCP raop_rtp_mirror_thread thread");
    if (raop_rtp_mirror->callbacks.video_stop) {
        raop_rtp_mirror->callbacks.video_stop(raop_rtp_mirror->callbacks.cls);
    }
#ifdef DUMP_H264
    fclose(file);
    fclose(file_source);
    fclose(file_len);
#endif
    return 0;
}

void
raop_rtp_start_mirror(raop_rtp_mirror_t *raop_rtp_mirror, int use_udp, unsigned short mirror_timing_rport, unsigned short * mirror_timing_lport,
                      unsigned short *mirror_data_lport)
{
    int use_ipv6 = 0;

    assert(raop_rtp_mirror);

    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    if (raop_rtp_mirror->running || !raop_rtp_mirror->joined) {
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        return;
    }

    //raop_rtp_mirror->mirror_timing_rport = mirror_timing_rport;
    if (raop_rtp_mirror->remote_saddr.ss_family == AF_INET6) {
        use_ipv6 = 1;
    }
    use_ipv6 = 0;
    if (raop_rtp_init_mirror_sockets(raop_rtp_mirror, use_ipv6) < 0) {
        logger_log(raop_rtp_mirror->logger, LOGGER_INFO, "Initializing sockets failed");
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        return;
    }
    if (mirror_timing_lport) *mirror_timing_lport = raop_rtp_mirror->mirror_timing_lport;
    if (mirror_data_lport) *mirror_data_lport = raop_rtp_mirror->mirror_data_lport;

    /* Create the thread and initialize running values */
    raop_rtp_mirror->running = 1;
    raop_rtp_mirror->joined = 0;

    THREAD_CREATE(raop_rtp_mirror->thread_mirror, raop_rtp_mirror_thread, raop_rtp_mirror);
    THREAD_CREATE(raop_rtp_mirror->thread_time, raop_rtp_mirror_thread_time, raop_rtp_mirror);
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
}

void raop_rtp_mirror_stop(raop_rtp_mirror_t *raop_rtp_mirror) {
    assert(raop_rtp_mirror);

    /* Check that we are running and thread is not
     * joined (should never be while still running) */
    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    if (!raop_rtp_mirror->running || raop_rtp_mirror->joined) {
        MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
        return;
    }
    raop_rtp_mirror->running = 0;
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);

    /* Join the thread */
    THREAD_JOIN(raop_rtp_mirror->thread_mirror);

    MUTEX_LOCK(raop_rtp_mirror->time_mutex);
    COND_SIGNAL(raop_rtp_mirror->time_cond);
    MUTEX_UNLOCK(raop_rtp_mirror->time_mutex);

    THREAD_JOIN(raop_rtp_mirror->thread_time);
    if (raop_rtp_mirror->mirror_data_sock != -1) closesocket(raop_rtp_mirror->mirror_data_sock);
    if (raop_rtp_mirror->mirror_time_sock != -1) closesocket(raop_rtp_mirror->mirror_time_sock);

    /* Mark thread as joined */
    MUTEX_LOCK(raop_rtp_mirror->run_mutex);
    raop_rtp_mirror->joined = 1;
    MUTEX_UNLOCK(raop_rtp_mirror->run_mutex);
}

void raop_rtp_mirror_destroy(raop_rtp_mirror_t *raop_rtp_mirror) {
    if (raop_rtp_mirror) {
        raop_rtp_mirror_stop(raop_rtp_mirror);
        MUTEX_DESTROY(raop_rtp_mirror->run_mutex);
        MUTEX_DESTROY(raop_rtp_mirror->time_mutex);
        COND_DESTROY(raop_rtp_mirror->time_cond);
        mirror_buffer_destroy(raop_rtp_mirror->buffer);
        free(raop_rtp_mirror);
    }
}

static int
raop_rtp_init_mirror_sockets(raop_rtp_mirror_t *raop_rtp_mirror, int use_ipv6)
{
    int dsock = -1, tsock = -1;
    unsigned short tport = 0, dport = 0;

    assert(raop_rtp_mirror);

    dsock = netutils_init_socket(&dport, use_ipv6, 0);
    tsock = netutils_init_socket(&tport, use_ipv6, 1);
    if (dsock == -1 || tsock == -1) {
        goto sockets_cleanup;
    }

    /* The timing thread must not block forever when the phone stops answering. */
    struct timeval tv = { 1, 0 };
    setsockopt(tsock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    /* Listen to the data socket if using TCP */
    if (listen(dsock, 1) < 0)
        goto sockets_cleanup;


    /* Set socket descriptors */
    raop_rtp_mirror->mirror_data_sock = dsock;
    raop_rtp_mirror->mirror_time_sock = tsock;

    /* Set port values */
    raop_rtp_mirror->mirror_data_lport = dport;
    raop_rtp_mirror->mirror_timing_lport = tport;
    return 0;

    sockets_cleanup:
    if (tsock != -1) closesocket(tsock);
    if (dsock != -1) closesocket(dsock);
    return -1;
}
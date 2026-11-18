<?php
/**
 * Smart Advisor - WhatsApp Inbox & Action Idempotency Layer
 * Version: 6.5.3
 *
 * Ensures every inbound message with a known identity is processed at most once,
 * even under concurrent webhook deliveries, timeouts, or network retries.
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_WhatsApp_Inbox {

    const TABLE_INBOX  = 'sa_whatsapp_inbox';
    const TABLE_ACTION = 'sa_action_idempotency';

    const STATUS_PROCESSING = 'PROCESSING';
    const STATUS_COMPLETED  = 'COMPLETED';
    const STATUS_FAILED     = 'FAILED';
    const STATUS_RETRYABLE  = 'RETRYABLE';

    const DEFAULT_LEASE_SECONDS = 60;
    const DEFAULT_RETENTION_DAYS = 14;

    /**
     * Normalized message identity extraction across supported WhatsApp gateways
     * (Meta Cloud API, Twilio, Evolution API, WAHA, AutoMate AI, Generic).
     *
     * @param WP_REST_Request $request
     * @return array
     */
    public static function extract_identity( $request ) {
        $params = method_exists( $request, 'get_json_params' ) ? $request->get_json_params() : [];
        if ( ! is_array( $params ) ) {
            $params = [];
        }

        $provider = self::detect_provider( $request, $params );
        $message_id_raw = self::extract_raw_message_id( $request, $params );
        $message_id = self::normalize_message_id( $message_id_raw );

        $sender_raw = self::extract_raw_sender( $request, $params );
        $trusted_phones = self::extract_trusted_phones( $request, $params );
        $is_group = self::detect_is_group( $request, $params );

        $retry_token = self::normalize_message_id(
            self::first_non_empty_string( [
                method_exists( $request, 'get_header' ) ? $request->get_header( 'X-Delivery-ID' ) : '',
                method_exists( $request, 'get_header' ) ? $request->get_header( 'X-Webhook-Delivery' ) : '',
                method_exists( $request, 'get_header' ) ? $request->get_header( 'X-Request-Id' ) : '',
            ] )
        );

        $identity = [
            'provider'        => $provider,
            'message_id'      => $message_id,
            'has_message_id'  => ( '' !== $message_id ),
            'sender'          => $sender_raw,
            'sender_hash'     => self::hash_value( $sender_raw !== '' ? $sender_raw : ( $trusted_phones[0] ?? 'unknown' ) ),
            'trusted_phones'  => $trusted_phones,
            'has_trusted_phone' => ! empty( $trusted_phones ),
            'is_group'        => $is_group,
            'retry_token'     => $retry_token,
        ];

        if ( function_exists( 'apply_filters' ) ) {
            $identity = apply_filters( 'sa_whatsapp_extract_identity', $identity, $request, $params );
        }

        return $identity;
    }

    /**
     * Detect gateway provider name safely.
     */
    public static function detect_provider( $request, $params = [] ) {
        $explicit = self::first_non_empty_string( [
            method_exists( $request, 'get_param' ) ? $request->get_param( 'provider' ) : '',
            method_exists( $request, 'get_header' ) ? $request->get_header( 'X-SA-Provider' ) : '',
            method_exists( $request, 'get_header' ) ? $request->get_header( 'X-WhatsApp-Provider' ) : '',
        ] );
        if ( '' !== $explicit ) {
            $clean = sanitize_key( $explicit );
            return '' !== $clean ? substr( $clean, 0, 40 ) : 'generic';
        }

        if ( isset( $params['object'] ) && 'whatsapp_business_account' === $params['object'] ) {
            return 'whatsapp_cloud';
        }
        if ( isset( $params['entry'][0]['changes'][0]['value']['messages'][0]['id'] ) ) {
            return 'whatsapp_cloud';
        }
        if ( ! empty( $params['SmsMessageSid'] ) || ! empty( $params['MessageSid'] ) ) {
            return 'twilio';
        }
        if ( isset( $params['key']['id'] ) || isset( $params['data']['key']['id'] ) ) {
            return 'evolution';
        }
        if ( isset( $params['payload']['id'] ) && isset( $params['event'] ) ) {
            return 'waha';
        }
        $app = method_exists( $request, 'get_param' ) ? (string) $request->get_param( 'app' ) : '';
        $idem_hdr = method_exists( $request, 'get_header' ) ? (string) $request->get_header( 'Idempotency-Key' ) : '';
        if ( false !== stripos( $app, 'whatsapp' ) || '' !== trim( $idem_hdr ) ) {
            return 'automate_ai';
        }

        return 'generic';
    }

    private static function extract_raw_message_id( $request, $params = [] ) {
        $param_getter = function( $key ) use ( $request, $params ) {
            if ( array_key_exists( $key, $params ) && is_scalar( $params[ $key ] ) ) {
                return (string) $params[ $key ];
            }
            if ( method_exists( $request, 'get_param' ) ) {
                $val = $request->get_param( $key );
                if ( is_scalar( $val ) ) {
                    return (string) $val;
                }
            }
            return '';
        };

        $candidates = [
            $param_getter( 'message_id' ),
            $param_getter( 'msg_id' ),
            $param_getter( 'msg_key' ),
            $param_getter( 'event_id' ),
            $param_getter( 'id' ),
            // Meta WhatsApp Cloud API
            self::nested_scalar( $params, [ 'entry', 0, 'changes', 0, 'value', 'messages', 0, 'id' ] ),
            self::nested_scalar( $params, [ 'messages', 0, 'id' ] ),
            // Evolution / Baileys
            self::nested_scalar( $params, [ 'key', 'id' ] ),
            self::nested_scalar( $params, [ 'data', 'key', 'id' ] ),
            // Twilio
            $param_getter( 'SmsMessageSid' ),
            $param_getter( 'MessageSid' ),
            // WAHA
            self::nested_scalar( $params, [ 'payload', 'id' ] ),
            // HTTP Headers
            method_exists( $request, 'get_header' ) ? (string) $request->get_header( 'X-Message-ID' ) : '',
            method_exists( $request, 'get_header' ) ? (string) $request->get_header( 'X-SA-Message-ID' ) : '',
            method_exists( $request, 'get_header' ) ? (string) $request->get_header( 'Idempotency-Key' ) : '',
        ];

        return self::first_non_empty_string( $candidates );
    }

    private static function extract_raw_sender( $request, $params = [] ) {
        $candidates = [
            method_exists( $request, 'get_param' ) ? $request->get_param( 'sender' ) : '',
            method_exists( $request, 'get_param' ) ? $request->get_param( 'phone' ) : '',
            method_exists( $request, 'get_param' ) ? $request->get_param( 'From' ) : '',
            method_exists( $request, 'get_param' ) ? $request->get_param( 'WaId' ) : '',
            self::nested_scalar( $params, [ 'entry', 0, 'changes', 0, 'value', 'contacts', 0, 'wa_id' ] ),
            self::nested_scalar( $params, [ 'entry', 0, 'changes', 0, 'value', 'messages', 0, 'from' ] ),
            self::nested_scalar( $params, [ 'key', 'remoteJid' ] ),
            self::nested_scalar( $params, [ 'data', 'key', 'remoteJid' ] ),
            self::nested_scalar( $params, [ 'payload', 'from' ] ),
        ];
        return self::first_non_empty_string( $candidates );
    }

    /**
     * Collect gateway-supplied trusted phone numbers/JIDs (NOT numbers parsed from message text).
     */
    public static function extract_trusted_phones( $request, $params = [] ) {
        $raw_sources = [
            method_exists( $request, 'get_param' ) ? $request->get_param( 'verified_phone' ) : '',
            method_exists( $request, 'get_header' ) ? $request->get_header( 'X-SA-Verified-Phone' ) : '',
            method_exists( $request, 'get_param' ) ? $request->get_param( 'phone' ) : '',
            method_exists( $request, 'get_param' ) ? $request->get_param( 'WaId' ) : '',
            method_exists( $request, 'get_param' ) ? $request->get_param( 'From' ) : '',
            self::nested_scalar( $params, [ 'entry', 0, 'changes', 0, 'value', 'contacts', 0, 'wa_id' ] ),
            self::nested_scalar( $params, [ 'entry', 0, 'changes', 0, 'value', 'messages', 0, 'from' ] ),
            self::nested_scalar( $params, [ 'key', 'remoteJid' ] ),
            self::nested_scalar( $params, [ 'data', 'key', 'remoteJid' ] ),
            self::nested_scalar( $params, [ 'payload', 'from' ] ),
        ];

        // Also accept sender if sender itself looks like a phone/JID rather than an arbitrary display name.
        $sender_param = method_exists( $request, 'get_param' ) ? (string) $request->get_param( 'sender' ) : '';
        if ( '' !== trim( $sender_param ) ) {
            $without_jid = preg_replace( '/@.*$/', '', trim( $sender_param ) );
            if ( preg_match( '/^\+?[0-9\s\-().]{7,20}$/', $without_jid ) ) {
                $raw_sources[] = $without_jid;
            }
        }

        $normalized = [];
        foreach ( $raw_sources as $src ) {
            if ( ! is_scalar( $src ) ) {
                continue;
            }
            $str = (string) $src;
            if ( false !== stripos( $str, '@g.us' ) ) {
                continue; // Group JID is never a personal phone identity.
            }
            $str = preg_replace( '/@.*$/', '', $str );
            $digits = preg_replace( '/[^0-9]/', '', $str );
            if ( strlen( $digits ) >= 7 && strlen( $digits ) <= 16 ) {
                $e164 = self::normalize_e164( $digits );
                if ( '' !== $e164 && ! in_array( $e164, $normalized, true ) ) {
                    $normalized[] = $e164;
                }
            }
        }

        return $normalized;
    }

    public static function detect_is_group( $request, $params = [] ) {
        $is_group = method_exists( $request, 'get_param' ) ? $request->get_param( 'is_group' ) : null;
        if ( $is_group ) {
            return true;
        }
        $group_name = method_exists( $request, 'get_param' ) ? trim( (string) $request->get_param( 'group_name' ) ) : '';
        if ( '' !== $group_name ) {
            return true;
        }
        $jids = [
            self::nested_scalar( $params, [ 'key', 'remoteJid' ] ),
            self::nested_scalar( $params, [ 'data', 'key', 'remoteJid' ] ),
            self::nested_scalar( $params, [ 'payload', 'from' ] ),
            method_exists( $request, 'get_param' ) ? (string) $request->get_param( 'sender' ) : '',
        ];
        foreach ( $jids as $jid ) {
            if ( false !== stripos( (string) $jid, '@g.us' ) ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verify whether the trusted sender owns the target phone number.
     *
     * Rules:
     * 1. Group chats NEVER own personal accounts -> deny.
     * 2. If gateway provides verified phone/JID(s), target MUST match one of them -> deny on mismatch.
     * 3. If gateway provided NO phone/JID (only display name, e.g. Android notification without number):
     *    - Deny if strict_sender_phone setting is enabled.
     *    - Otherwise allow only when target_phone is valid and caller enforces per-sender rate-limits & role checks.
     *
     * @param WP_REST_Request $request
     * @param string          $target_phone
     * @param array           $config
     * @return array{allowed:bool,reason:string}
     */
    public static function verify_sender_phone_ownership( $request, $target_phone, $config = [] ) {
        $params = method_exists( $request, 'get_json_params' ) ? $request->get_json_params() : [];
        if ( ! is_array( $params ) ) {
            $params = [];
        }

        if ( self::detect_is_group( $request, $params ) ) {
            return [ 'allowed' => false, 'reason' => 'group_not_allowed' ];
        }

        $target_digits = preg_replace( '/[^0-9]/', '', (string) $target_phone );
        if ( strlen( $target_digits ) < 7 || strlen( $target_digits ) > 16 ) {
            return [ 'allowed' => false, 'reason' => 'invalid_target_phone' ];
        }
        $target_e164 = self::normalize_e164( $target_digits );

        $trusted_phones = self::extract_trusted_phones( $request, $params );
        if ( ! empty( $trusted_phones ) ) {
            foreach ( $trusted_phones as $tp ) {
                if ( hash_equals( $tp, $target_e164 ) ) {
                    return [ 'allowed' => true, 'reason' => 'verified_phone_match' ];
                }
            }
            return [ 'allowed' => false, 'reason' => 'sender_phone_mismatch' ];
        }

        if ( ! empty( $config['strict_sender_phone'] ) ) {
            return [ 'allowed' => false, 'reason' => 'missing_verified_sender_phone' ];
        }

        return [ 'allowed' => true, 'reason' => 'display_sender_allowed' ];
    }

    public static function normalize_e164( $clean, $cc = '967' ) {
        $d = preg_replace( '/[^0-9]/', '', (string) $clean );
        if ( '' === $d ) {
            return '';
        }
        if ( 0 === strpos( $d, $cc ) && strlen( $d ) >= strlen( $cc ) + 6 ) {
            return $d;
        }
        if ( '00' === substr( $d, 0, 2 ) ) {
            $d = substr( $d, 2 );
            if ( strlen( $d ) >= 10 ) {
                return $d;
            }
        }
        if ( '0' === substr( $d, 0, 1 ) ) {
            $d = substr( $d, 1 );
        }
        return $cc . $d;
    }

    public static function normalize_message_id( $raw ) {
        $clean = trim( (string) $raw );
        $clean = preg_replace( '/[\x00-\x1F\x7F\s]+/', '', $clean );
        if ( '' === $clean ) {
            return '';
        }
        if ( strlen( $clean ) > 191 ) {
            return 'sha256:' . hash( 'sha256', $clean );
        }
        return $clean;
    }

    public static function record_missing_message_id( $provider, $sender_hash ) {
        $count = (int) get_option( 'sa_wa_missing_msg_id_count', 0 );
        update_option( 'sa_wa_missing_msg_id_count', $count + 1, false );
        if ( ( $count % 10 ) === 0 ) {
            error_log( sprintf( '[Smart Advisor] Inbound WhatsApp request without message_id (provider=%s, sender=%s).', sanitize_key( $provider ), substr( (string) $sender_hash, 0, 12 ) ) );
        }
    }

    /**
     * Atomically acquire processing lock in sa_whatsapp_inbox.
     *
     * Outcomes:
     *  - acquired         => caller owns lease (id, owner_token, attempt_count)
     *  - replay           => already COMPLETED; return saved row response_payload + http_status
     *  - failed_permanent => already FAILED; return saved error response
     *  - in_flight        => active PROCESSING with unexpired lease -> HTTP 409 + Retry-After
     *  - db_error         => DB failure -> Fail Closed HTTP 503
     */
    public static function acquire( $provider, $message_id, $sender_hash, $lease_seconds = self::DEFAULT_LEASE_SECONDS ) {
        if ( class_exists( 'SA_Customer_Database' ) ) {
            SA_Customer_Database::maybe_upgrade();
        }

        global $wpdb;
        $table = $wpdb->prefix . self::TABLE_INBOX;
        $provider = substr( sanitize_key( $provider ) ?: 'generic', 0, 40 );
        $message_id = self::normalize_message_id( $message_id );
        if ( '' === $message_id ) {
            return [ 'outcome' => 'no_message_id' ];
        }

        $lease_seconds = max( 10, min( 300, (int) $lease_seconds ) );
        $owner_token = self::generate_owner_token();
        $now_ts = time();
        $now = gmdate( 'Y-m-d H:i:s', $now_ts );
        $lease_until = gmdate( 'Y-m-d H:i:s', $now_ts + $lease_seconds );

        $inserted = $wpdb->query( $wpdb->prepare(
            "INSERT IGNORE INTO {$table}
             (provider, message_id, sender_hash, owner_token, status, http_status, created_at, updated_at, lease_expires_at, attempt_count)
             VALUES (%s, %s, %s, %s, %s, 200, %s, %s, %s, 1)",
            $provider,
            $message_id,
            $sender_hash,
            $owner_token,
            self::STATUS_PROCESSING,
            $now,
            $now,
            $lease_until
        ) );

        if ( false === $inserted ) {
            return [ 'outcome' => 'db_error', 'error' => (string) $wpdb->last_error ];
        }

        if ( 1 === (int) $inserted ) {
            $id = (int) $wpdb->insert_id;
            if ( $id <= 0 ) {
                $id = (int) $wpdb->get_var( $wpdb->prepare(
                    "SELECT id FROM {$table} WHERE provider = %s AND message_id = %s LIMIT 1",
                    $provider,
                    $message_id
                ) );
            }
            if ( mt_rand( 1, 100 ) === 1 ) {
                self::purge_old_records();
            }
            return [
                'outcome'       => 'acquired',
                'id'            => $id,
                'owner_token'   => $owner_token,
                'attempt_count' => 1,
                'reclaimed'     => false,
            ];
        }

        $row = $wpdb->get_row( $wpdb->prepare(
            "SELECT * FROM {$table} WHERE provider = %s AND message_id = %s LIMIT 1",
            $provider,
            $message_id
        ), ARRAY_A );

        if ( ! is_array( $row ) || empty( $row['id'] ) ) {
            return [ 'outcome' => 'db_error', 'error' => 'missing_duplicate_inbox_row' ];
        }

        $status = (string) ( $row['status'] ?? '' );
        if ( self::STATUS_COMPLETED === $status ) {
            return [ 'outcome' => 'replay', 'row' => $row ];
        }
        if ( self::STATUS_FAILED === $status ) {
            return [ 'outcome' => 'failed_permanent', 'row' => $row ];
        }

        $lease_exp_str = (string) ( $row['lease_expires_at'] ?? '' );
        $lease_exp_ts = '' !== $lease_exp_str ? strtotime( $lease_exp_str . ' UTC' ) : 0;

        if ( self::STATUS_PROCESSING === $status && $lease_exp_ts > $now_ts ) {
            return [
                'outcome'     => 'in_flight',
                'row'         => $row,
                'retry_after' => max( 2, min( 15, $lease_exp_ts - $now_ts ) ),
            ];
        }

        // Eligible for lease recovery (RETRYABLE or expired PROCESSING lease).
        $updated = $wpdb->query( $wpdb->prepare(
            "UPDATE {$table}
             SET status = %s,
                 owner_token = %s,
                 lease_expires_at = %s,
                 attempt_count = attempt_count + 1,
                 updated_at = %s
             WHERE id = %d
               AND (
                   status = %s
                   OR (status = %s AND (lease_expires_at IS NULL OR lease_expires_at <= %s))
               )",
            self::STATUS_PROCESSING,
            $owner_token,
            $lease_until,
            $now,
            (int) $row['id'],
            self::STATUS_RETRYABLE,
            self::STATUS_PROCESSING,
            $now
        ) );

        if ( false === $updated ) {
            return [ 'outcome' => 'db_error', 'error' => (string) $wpdb->last_error ];
        }

        if ( 1 === (int) $updated ) {
            return [
                'outcome'       => 'acquired',
                'id'            => (int) $row['id'],
                'owner_token'   => $owner_token,
                'attempt_count' => (int) ( $row['attempt_count'] ?? 1 ) + 1,
                'reclaimed'     => true,
            ];
        }

        // Re-check once after concurrent race.
        $latest = $wpdb->get_row( $wpdb->prepare(
            "SELECT * FROM {$table} WHERE id = %d LIMIT 1",
            (int) $row['id']
        ), ARRAY_A );
        if ( is_array( $latest ) && self::STATUS_COMPLETED === ( $latest['status'] ?? '' ) ) {
            return [ 'outcome' => 'replay', 'row' => $latest ];
        }
        if ( is_array( $latest ) && self::STATUS_FAILED === ( $latest['status'] ?? '' ) ) {
            return [ 'outcome' => 'failed_permanent', 'row' => $latest ];
        }

        return [
            'outcome'     => 'in_flight',
            'row'         => is_array( $latest ) ? $latest : $row,
            'retry_after' => 5,
        ];
    }

    /**
     * Mark inbox record COMPLETED with cached response payload (owner-token gated).
     */
    public static function complete( $id, $owner_token, $payload_array, $http_status = 200 ) {
        $id = absint( $id );
        if ( ! $id || '' === (string) $owner_token ) {
            return false;
        }
        global $wpdb;
        $table = $wpdb->prefix . self::TABLE_INBOX;
        $now = gmdate( 'Y-m-d H:i:s', time() );
        $encoded = is_string( $payload_array ) ? $payload_array : wp_json_encode( $payload_array );

        $updated = $wpdb->query( $wpdb->prepare(
            "UPDATE {$table}
             SET status = %s,
                 response_payload = %s,
                 http_status = %d,
                 lease_expires_at = NULL,
                 owner_token = NULL,
                 last_error = NULL,
                 updated_at = %s
             WHERE id = %d
               AND owner_token = %s
               AND status = %s",
            self::STATUS_COMPLETED,
            (string) $encoded,
            (int) $http_status,
            $now,
            $id,
            (string) $owner_token,
            self::STATUS_PROCESSING
        ) );

        return 1 === (int) $updated;
    }

    /**
     * Mark inbox record RETRYABLE or FAILED (owner-token gated).
     */
    public static function fail( $id, $owner_token, $status, $error_message = '', $payload_array = null, $http_status = 503 ) {
        $id = absint( $id );
        if ( ! $id || '' === (string) $owner_token ) {
            return false;
        }
        if ( ! in_array( $status, [ self::STATUS_RETRYABLE, self::STATUS_FAILED ], true ) ) {
            $status = self::STATUS_RETRYABLE;
        }
        global $wpdb;
        $table = $wpdb->prefix . self::TABLE_INBOX;
        $now = gmdate( 'Y-m-d H:i:s', time() );
        $encoded = null === $payload_array
            ? null
            : ( is_string( $payload_array ) ? $payload_array : wp_json_encode( $payload_array ) );

        $updated = $wpdb->query( $wpdb->prepare(
            "UPDATE {$table}
             SET status = %s,
                 response_payload = %s,
                 http_status = %d,
                 lease_expires_at = NULL,
                 owner_token = NULL,
                 last_error = %s,
                 updated_at = %s
             WHERE id = %d
               AND owner_token = %s
               AND status = %s",
            $status,
            $encoded,
            (int) $http_status,
            substr( sanitize_text_field( (string) $error_message ), 0, 500 ),
            $now,
            $id,
            (string) $owner_token,
            self::STATUS_PROCESSING
        ) );

        return 1 === (int) $updated;
    }

    /**
     * Extend active PROCESSING lease for slow operations (owner-token gated).
     */
    public static function extend_lease( $id, $owner_token, $extra_seconds = self::DEFAULT_LEASE_SECONDS ) {
        $id = absint( $id );
        if ( ! $id || '' === (string) $owner_token ) {
            return false;
        }
        global $wpdb;
        $table = $wpdb->prefix . self::TABLE_INBOX;
        $now_ts = time();
        $now = gmdate( 'Y-m-d H:i:s', $now_ts );
        $lease_until = gmdate( 'Y-m-d H:i:s', $now_ts + max( 10, min( 300, (int) $extra_seconds ) ) );

        $updated = $wpdb->query( $wpdb->prepare(
            "UPDATE {$table}
             SET lease_expires_at = %s,
                 updated_at = %s
             WHERE id = %d
               AND owner_token = %s
               AND status = %s",
            $lease_until,
            $now,
            $id,
            (string) $owner_token,
            self::STATUS_PROCESSING
        ) );
        return 1 === (int) $updated;
    }

    /**
     * Generic action idempotency lock/replay for endpoints such as account-action and cart confirmation.
     */
    public static function acquire_action( $scope, $idempotency_key, $actor_hash = '', $lease_seconds = 30 ) {
        if ( class_exists( 'SA_Customer_Database' ) ) {
            SA_Customer_Database::maybe_upgrade();
        }
        global $wpdb;
        $table = $wpdb->prefix . self::TABLE_ACTION;
        $scope = substr( sanitize_key( $scope ) ?: 'action', 0, 40 );
        $key = self::normalize_message_id( $idempotency_key );
        if ( '' === $key ) {
            return [ 'outcome' => 'no_key' ];
        }

        $owner_token = self::generate_owner_token();
        $now_ts = time();
        $now = gmdate( 'Y-m-d H:i:s', $now_ts );
        $lease_until = gmdate( 'Y-m-d H:i:s', $now_ts + max( 5, min( 120, (int) $lease_seconds ) ) );

        $inserted = $wpdb->query( $wpdb->prepare(
            "INSERT IGNORE INTO {$table}
             (scope, idempotency_key, actor_hash, owner_token, status, http_status, created_at, updated_at, lease_expires_at, attempt_count)
             VALUES (%s, %s, %s, %s, %s, 200, %s, %s, %s, 1)",
            $scope,
            $key,
            $actor_hash,
            $owner_token,
            self::STATUS_PROCESSING,
            $now,
            $now,
            $lease_until
        ) );

        if ( false === $inserted ) {
            return [ 'outcome' => 'db_error' ];
        }

        if ( 1 === (int) $inserted ) {
            $id = (int) $wpdb->insert_id;
            if ( $id <= 0 ) {
                $id = (int) $wpdb->get_var( $wpdb->prepare(
                    "SELECT id FROM {$table} WHERE scope = %s AND idempotency_key = %s LIMIT 1",
                    $scope,
                    $key
                ) );
            }
            return [
                'outcome'     => 'acquired',
                'id'          => $id,
                'owner_token' => $owner_token,
            ];
        }

        $row = $wpdb->get_row( $wpdb->prepare(
            "SELECT * FROM {$table} WHERE scope = %s AND idempotency_key = %s LIMIT 1",
            $scope,
            $key
        ), ARRAY_A );
        if ( ! is_array( $row ) || empty( $row['id'] ) ) {
            return [ 'outcome' => 'db_error' ];
        }

        $status = (string) ( $row['status'] ?? '' );
        if ( self::STATUS_COMPLETED === $status ) {
            return [ 'outcome' => 'replay', 'row' => $row ];
        }
        if ( self::STATUS_FAILED === $status ) {
            return [ 'outcome' => 'failed_permanent', 'row' => $row ];
        }

        $lease_exp_str = (string) ( $row['lease_expires_at'] ?? '' );
        $lease_exp_ts = '' !== $lease_exp_str ? strtotime( $lease_exp_str . ' UTC' ) : 0;
        if ( self::STATUS_PROCESSING === $status && $lease_exp_ts > $now_ts ) {
            return [
                'outcome'     => 'in_flight',
                'row'         => $row,
                'retry_after' => max( 2, min( 15, $lease_exp_ts - $now_ts ) ),
            ];
        }

        $updated = $wpdb->query( $wpdb->prepare(
            "UPDATE {$table}
             SET status = %s,
                 owner_token = %s,
                 lease_expires_at = %s,
                 attempt_count = attempt_count + 1,
                 updated_at = %s
             WHERE id = %d
               AND (
                   status = %s
                   OR (status = %s AND (lease_expires_at IS NULL OR lease_expires_at <= %s))
               )",
            self::STATUS_PROCESSING,
            $owner_token,
            $lease_until,
            $now,
            (int) $row['id'],
            self::STATUS_RETRYABLE,
            self::STATUS_PROCESSING,
            $now
        ) );

        if ( 1 === (int) $updated ) {
            return [
                'outcome'     => 'acquired',
                'id'          => (int) $row['id'],
                'owner_token' => $owner_token,
            ];
        }

        $latest = $wpdb->get_row( $wpdb->prepare(
            "SELECT * FROM {$table} WHERE id = %d LIMIT 1",
            (int) $row['id']
        ), ARRAY_A );
        if ( is_array( $latest ) && self::STATUS_COMPLETED === ( $latest['status'] ?? '' ) ) {
            return [ 'outcome' => 'replay', 'row' => $latest ];
        }
        return [ 'outcome' => 'in_flight', 'retry_after' => 5 ];
    }

    public static function complete_action( $id, $owner_token, $payload_array, $http_status = 200 ) {
        $id = absint( $id );
        if ( ! $id || '' === (string) $owner_token ) {
            return false;
        }
        global $wpdb;
        $table = $wpdb->prefix . self::TABLE_ACTION;
        $now = gmdate( 'Y-m-d H:i:s', time() );
        $encoded = is_string( $payload_array ) ? $payload_array : wp_json_encode( $payload_array );

        $updated = $wpdb->query( $wpdb->prepare(
            "UPDATE {$table}
             SET status = %s,
                 response_payload = %s,
                 http_status = %d,
                 lease_expires_at = NULL,
                 owner_token = NULL,
                 last_error = NULL,
                 updated_at = %s
             WHERE id = %d AND owner_token = %s AND status = %s",
            self::STATUS_COMPLETED,
            (string) $encoded,
            (int) $http_status,
            $now,
            $id,
            (string) $owner_token,
            self::STATUS_PROCESSING
        ) );
        return 1 === (int) $updated;
    }

    public static function fail_action( $id, $owner_token, $status, $error_message = '', $payload_array = null, $http_status = 503 ) {
        $id = absint( $id );
        if ( ! $id || '' === (string) $owner_token ) {
            return false;
        }
        if ( ! in_array( $status, [ self::STATUS_RETRYABLE, self::STATUS_FAILED ], true ) ) {
            $status = self::STATUS_RETRYABLE;
        }
        global $wpdb;
        $table = $wpdb->prefix . self::TABLE_ACTION;
        $now = gmdate( 'Y-m-d H:i:s', time() );
        $encoded = null === $payload_array
            ? null
            : ( is_string( $payload_array ) ? $payload_array : wp_json_encode( $payload_array ) );

        $updated = $wpdb->query( $wpdb->prepare(
            "UPDATE {$table}
             SET status = %s,
                 response_payload = %s,
                 http_status = %d,
                 lease_expires_at = NULL,
                 owner_token = NULL,
                 last_error = %s,
                 updated_at = %s
             WHERE id = %d AND owner_token = %s AND status = %s",
            $status,
            $encoded,
            (int) $http_status,
            substr( sanitize_text_field( (string) $error_message ), 0, 500 ),
            $now,
            $id,
            (string) $owner_token,
            self::STATUS_PROCESSING
        ) );
        return 1 === (int) $updated;
    }

    /**
     * Build WP_REST_Response from a cached inbox/action row with Idempotency headers.
     */
    public static function replay_response_from_row( $row, $default_reply = '' ) {
        $http_status = max( 200, min( 599, (int) ( $row['http_status'] ?? 200 ) ) );
        $raw = (string) ( $row['response_payload'] ?? '' );
        $decoded = '' !== $raw ? json_decode( $raw, true ) : null;
        if ( ! is_array( $decoded ) ) {
            $decoded = [ 'reply' => $default_reply ];
        }
        $resp = new WP_REST_Response( $decoded, $http_status );
        $resp->header( 'Idempotency-Supported', 'true' );
        $resp->header( 'Idempotency-Replayed', 'true' );
        return $resp;
    }

    /**
     * Clean up completed/failed records older than configured retention days (default 14 days).
     */
    public static function purge_old_records( $retention_days = null ) {
        if ( null === $retention_days && class_exists( 'SA_WhatsApp_Settings' ) ) {
            $cfg = SA_WhatsApp_Settings::get_config();
            $retention_days = absint( $cfg['inbox_retention_days'] ?? self::DEFAULT_RETENTION_DAYS );
        }
        $days = max( 1, min( 90, absint( $retention_days ?: self::DEFAULT_RETENTION_DAYS ) ) );
        $cutoff = gmdate( 'Y-m-d H:i:s', time() - ( $days * 86400 ) );

        global $wpdb;
        $inbox_table = $wpdb->prefix . self::TABLE_INBOX;
        $action_table = $wpdb->prefix . self::TABLE_ACTION;

        $wpdb->query( $wpdb->prepare(
            "DELETE FROM {$inbox_table} WHERE status IN (%s, %s, %s) AND updated_at < %s LIMIT 500",
            self::STATUS_COMPLETED,
            self::STATUS_FAILED,
            self::STATUS_RETRYABLE,
            $cutoff
        ) );
        $wpdb->query( $wpdb->prepare(
            "DELETE FROM {$action_table} WHERE status IN (%s, %s, %s) AND updated_at < %s LIMIT 500",
            self::STATUS_COMPLETED,
            self::STATUS_FAILED,
            self::STATUS_RETRYABLE,
            $cutoff
        ) );
    }

    public static function hash_value( $raw ) {
        $secret = defined( 'AUTH_KEY' ) ? AUTH_KEY : ( function_exists( 'wp_salt' ) ? wp_salt( 'auth' ) : 'sa_salt' );
        return hash_hmac( 'sha256', strtolower( trim( (string) $raw ) ), $secret );
    }

    private static function generate_owner_token() {
        try {
            return bin2hex( random_bytes( 16 ) );
        } catch ( Exception $e ) {
            return substr( md5( wp_generate_password( 32, true, true ) . microtime( true ) ), 0, 32 );
        }
    }

    private static function nested_scalar( $data, $path ) {
        $cur = $data;
        foreach ( $path as $segment ) {
            if ( ! is_array( $cur ) || ! array_key_exists( $segment, $cur ) ) {
                return '';
            }
            $cur = $cur[ $segment ];
        }
        return is_scalar( $cur ) ? (string) $cur : '';
    }

    private static function first_non_empty_string( $candidates ) {
        foreach ( (array) $candidates as $item ) {
            if ( is_scalar( $item ) ) {
                $trimmed = trim( (string) $item );
                if ( '' !== $trimmed ) {
                    return $trimmed;
                }
            }
        }
        return '';
    }
}

<?php
/**
 * Verification Suite for Smart Advisor 6.5.3:
 * WhatsApp Inbox Idempotency, Lease Recovery, Concurrency Guards,
 * Magic Link / Registration Replay Safety, Rate-Limiting & Cleanup.
 *
 * Run on any PHP 7.4+ host:
 *   php tests/test-6.5.3-inbox-idempotency.php
 */

if ( ! defined( 'ABSPATH' ) ) {
    define( 'ABSPATH', dirname( __DIR__ ) . '/' );
}
if ( ! defined( 'AUTH_KEY' ) ) {
    define( 'AUTH_KEY', 'test-auth-key-smart-advisor-6.5.3' );
}
if ( ! defined( 'MINUTE_IN_SECONDS' ) ) {
    define( 'MINUTE_IN_SECONDS', 60 );
}
if ( ! defined( 'ARRAY_A' ) ) {
    define( 'ARRAY_A', 'ARRAY_A' );
}

// -------------------------------------------------------------------------
// Minimal WordPress & $wpdb Mock Harness
// -------------------------------------------------------------------------

class SA_Test_WP_Error {
    public $code;
    public $message;
    public $data;
    public function __construct( $code = '', $message = '', $data = '' ) {
        $this->code    = $code;
        $this->message = $message;
        $this->data    = $data;
    }
    public function get_error_code() { return $this->code; }
    public function get_error_message() { return $this->message; }
    public function get_error_data() { return $this->data; }
}
if ( ! class_exists( 'WP_Error' ) ) {
    class_alias( 'SA_Test_WP_Error', 'WP_Error' );
}

class SA_Test_REST_Request {
    private $params = [];
    private $headers = [];
    private $json = [];
    public function __construct( array $params = [], array $headers = [], array $json = null ) {
        $this->params  = $params;
        $this->json    = null === $json ? $params : $json;
        foreach ( $headers as $k => $v ) {
            $this->headers[ strtolower( $k ) ] = $v;
        }
    }
    public function get_param( $key ) {
        return $this->params[ $key ] ?? ( $this->json[ $key ] ?? null );
    }
    public function get_json_params() {
        return $this->json;
    }
    public function get_header( $name ) {
        return $this->headers[ strtolower( $name ) ] ?? '';
    }
}
if ( ! class_exists( 'WP_REST_Request' ) ) {
    class_alias( 'SA_Test_REST_Request', 'WP_REST_Request' );
}

class SA_Test_REST_Response {
    public $data;
    public $status;
    public $headers = [];
    public function __construct( $data = null, $status = 200 ) {
        $this->data   = $data;
        $this->status = (int) $status;
    }
    public function header( $key, $value ) {
        $this->headers[ $key ] = (string) $value;
    }
    public function get_data() { return $this->data; }
    public function get_status() { return $this->status; }
    public function get_headers() { return $this->headers; }
}
if ( ! class_exists( 'WP_REST_Response' ) ) {
    class_alias( 'SA_Test_REST_Response', 'WP_REST_Response' );
}

if ( ! class_exists( 'WP_User' ) ) {
    class WP_User {
        public $ID;
        public $user_status = 0;
        public $roles = [];
        public function __construct( $id = 0 ) { $this->ID = (int) $id; }
        public function set_role( $role ) {
            global $sa_test_user_roles;
            $this->roles = [ $role ];
            $sa_test_user_roles[ $this->ID ] = $role;
        }
    }
}

$sa_test_options     = [];
$sa_test_transients  = [];
$sa_test_users       = [];
$sa_test_user_meta   = [];
$sa_test_user_roles  = [];
$sa_test_filters     = [];
$sa_test_next_uid    = 100;

function is_wp_error( $thing ) { return $thing instanceof WP_Error || $thing instanceof SA_Test_WP_Error; }
function absint( $v ) { return abs( (int) $v ); }
function wp_salt( $scheme = 'auth' ) { return AUTH_KEY . '_' . $scheme; }
function wp_json_encode( $data ) { return json_encode( $data, JSON_UNESCAPED_UNICODE ); }
function sanitize_key( $k ) { return strtolower( preg_replace( '/[^a-zA-Z0-9_\-]/', '', (string) $k ) ); }
function sanitize_text_field( $s ) { return trim( strip_tags( (string) $s ) ); }
function sanitize_email( $s ) { return trim( (string) $s ); }
function is_email( $s ) { return false !== filter_var( $s, FILTER_VALIDATE_EMAIL ); }
function wp_unslash( $s ) { return $s; }
function esc_url_raw( $u ) { return (string) $u; }
function esc_html( $s ) { return htmlspecialchars( (string) $s, ENT_QUOTES, 'UTF-8' ); }
function wp_strip_all_tags( $s ) { return trim( strip_tags( (string) $s ) ); }
function home_url( $path = '/' ) { return 'https://example.test/' . ltrim( (string) $path, '/' ); }
function get_bloginfo( $show = '' ) { return 'متجر التجربة'; }
function add_query_arg( $args, $url ) {
    $sep = ( false === strpos( $url, '?' ) ) ? '?' : '&';
    return $url . $sep . http_build_query( $args );
}
function add_action( $tag, $cb, $prio = 10 ) {}
function register_rest_route( $ns, $route, $args ) {}
function apply_filters( $tag, $val ) { return $val; }
function rest_sanitize_boolean( $val ) { return filter_var( $val, FILTER_VALIDATE_BOOLEAN ); }
function wp_generate_password( $length = 12, $special = true, $extra = false ) {
    return substr( bin2hex( random_bytes( max( 16, (int) $length ) ) ), 0, (int) $length );
}
function wp_rand( $min = 0, $max = 999 ) { return mt_rand( (int) $min, (int) $max ); }

function get_option( $name, $default = false ) {
    global $sa_test_options;
    return array_key_exists( $name, $sa_test_options ) ? $sa_test_options[ $name ] : $default;
}
function update_option( $name, $val, $autoload = null ) {
    global $sa_test_options;
    $sa_test_options[ $name ] = $val;
    return true;
}
function add_option( $name, $val, $dep = '', $autoload = 'yes' ) {
    global $sa_test_options;
    if ( array_key_exists( $name, $sa_test_options ) ) return false;
    $sa_test_options[ $name ] = $val;
    return true;
}
function delete_option( $name ) {
    global $sa_test_options;
    unset( $sa_test_options[ $name ] );
    return true;
}
function get_transient( $key ) {
    global $sa_test_transients;
    return $sa_test_transients[ $key ] ?? false;
}
function set_transient( $key, $val, $exp = 0 ) {
    global $sa_test_transients;
    $sa_test_transients[ $key ] = $val;
    return true;
}

function get_userdata( $uid ) {
    global $sa_test_users;
    return $sa_test_users[ (int) $uid ] ?? false;
}
function get_user_by( $field, $value ) {
    return 'id' === $field ? get_userdata( $value ) : false;
}
function get_user_meta( $uid, $key, $single = false ) {
    global $sa_test_user_meta;
    $val = $sa_test_user_meta[ (int) $uid ][ $key ] ?? '';
    return $single ? $val : [ $val ];
}
function update_user_meta( $uid, $key, $val ) {
    global $sa_test_user_meta;
    $sa_test_user_meta[ (int) $uid ][ $key ] = $val;
    return true;
}
function delete_user_meta( $uid, $key ) {
    global $sa_test_user_meta;
    unset( $sa_test_user_meta[ (int) $uid ][ $key ] );
    return true;
}
function user_can( $uid, $cap ) {
    global $sa_test_user_roles;
    $role = $sa_test_user_roles[ (int) $uid ] ?? 'customer';
    return in_array( $cap, [ 'manage_options', 'edit_others_posts' ], true ) && in_array( $role, [ 'administrator', 'editor' ], true );
}
function username_exists( $username ) {
    global $sa_test_users;
    foreach ( $sa_test_users as $u ) {
        if ( isset( $u->user_login ) && $u->user_login === $username ) return $u->ID;
    }
    return false;
}
function email_exists( $email ) {
    global $sa_test_users;
    foreach ( $sa_test_users as $u ) {
        if ( isset( $u->user_email ) && $u->user_email === $email ) return $u->ID;
    }
    return false;
}
function wp_create_user( $username, $password, $email = '' ) {
    global $sa_test_users, $sa_test_next_uid, $sa_test_reg_creates;
    $sa_test_reg_creates++;
    $uid = $sa_test_next_uid++;
    $u = new WP_User( $uid );
    $u->user_login = $username;
    $u->user_email = $email;
    $sa_test_users[ $uid ] = $u;
    return $uid;
}
function wp_update_user( $args ) { return $args['ID'] ?? 0; }
function wp_set_password( $p, $uid ) {}
function wp_clear_auth_cookie() {}
function wp_set_current_user( $uid ) {}
function wp_set_auth_cookie( $uid, $remember = false ) {}
function wp_safe_redirect( $url ) {}

/**
 * In-memory $wpdb simulator matching Smart Advisor's exact SQL contracts.
 */
class SA_Mock_WPDB {
    public $prefix = 'wp_';
    public $options = 'wp_options';
    public $usermeta = 'wp_usermeta';
    public $last_error = '';
    public $insert_id = 0;
    public $simulate_db_failure = false;

    public $inbox = [];   // id => row
    public $actions = []; // id => row
    private $next_inbox_id = 1;
    private $next_action_id = 1;

    public function get_charset_collate() { return 'DEFAULT CHARSET=utf8mb4'; }

    public function prepare( $query, ...$args ) {
        if ( 1 === count( $args ) && is_array( $args[0] ) ) {
            $args = $args[0];
        }
        return [ 'sql' => $query, 'args' => $args ];
    }

    public function query( $prepared ) {
        global $sa_test_options;
        if ( $this->simulate_db_failure ) {
            $this->last_error = 'Simulated database error';
            return false;
        }
        $sql  = is_array( $prepared ) ? $prepared['sql'] : (string) $prepared;
        $args = is_array( $prepared ) ? $prepared['args'] : [];

        // 1) Atomic options counter (rate limits)
        if ( false !== strpos( $sql, "INSERT INTO {$this->options}" ) && false !== strpos( $sql, 'ON DUPLICATE KEY UPDATE' ) ) {
            $name = (string) $args[0];
            if ( isset( $sa_test_options[ $name ] ) ) {
                $sa_test_options[ $name ] = (string) ( (int) $sa_test_options[ $name ] + 1 );
                return 2;
            }
            $sa_test_options[ $name ] = '1';
            return 1;
        }
        if ( false !== strpos( $sql, "INSERT IGNORE INTO {$this->options}" ) ) {
            $name = (string) $args[0];
            $val  = (string) $args[1];
            if ( ! isset( $sa_test_options[ $name ] ) ) {
                $sa_test_options[ $name ] = $val;
                return 1;
            }
            return 0;
        }
        if ( false !== strpos( $sql, "DELETE counter FROM {$this->options}" ) || false !== strpos( $sql, "DELETE FROM {$this->options}" ) ) {
            return 0;
        }

        // 2) sa_whatsapp_inbox INSERT IGNORE
        if ( false !== strpos( $sql, "INSERT IGNORE INTO {$this->prefix}sa_whatsapp_inbox" ) ) {
            list( $provider, $msg_id, $sender_hash, $owner_token, $status, $now, $now2, $lease_until ) = $args;
            foreach ( $this->inbox as $row ) {
                if ( $row['provider'] === $provider && $row['message_id'] === $msg_id ) {
                    $this->insert_id = 0;
                    return 0;
                }
            }
            $id = $this->next_inbox_id++;
            $this->inbox[ $id ] = [
                'id'               => $id,
                'provider'         => $provider,
                'message_id'       => $msg_id,
                'sender_hash'      => $sender_hash,
                'owner_token'      => $owner_token,
                'status'           => $status,
                'response_payload' => null,
                'http_status'      => 200,
                'created_at'       => $now,
                'updated_at'       => $now2,
                'lease_expires_at' => $lease_until,
                'attempt_count'    => 1,
                'last_error'       => null,
            ];
            $this->insert_id = $id;
            return 1;
        }

        // 3) sa_whatsapp_inbox lease recovery UPDATE
        if ( false !== strpos( $sql, "UPDATE {$this->prefix}sa_whatsapp_inbox" ) && false !== strpos( $sql, 'attempt_count = attempt_count + 1' ) ) {
            list( $new_status, $owner_token, $lease_until, $now, $id, $retryable_status, $proc_status, $now_cmp ) = $args;
            if ( ! isset( $this->inbox[ $id ] ) ) return 0;
            $row = $this->inbox[ $id ];
            $eligible = ( $row['status'] === $retryable_status ) ||
                        ( $row['status'] === $proc_status && ( null === $row['lease_expires_at'] || $row['lease_expires_at'] <= $now_cmp ) );
            if ( ! $eligible ) return 0;
            $this->inbox[ $id ]['status']           = $new_status;
            $this->inbox[ $id ]['owner_token']      = $owner_token;
            $this->inbox[ $id ]['lease_expires_at'] = $lease_until;
            $this->inbox[ $id ]['attempt_count']    = (int) $row['attempt_count'] + 1;
            $this->inbox[ $id ]['updated_at']       = $now;
            return 1;
        }

        // 4) sa_whatsapp_inbox complete()
        if ( false !== strpos( $sql, "UPDATE {$this->prefix}sa_whatsapp_inbox" ) && false !== strpos( $sql, 'response_payload = %s' ) && false === strpos( $sql, 'last_error = %s' ) ) {
            list( $status, $payload, $http_status, $now, $id, $owner_token, $req_status ) = $args;
            if ( ! isset( $this->inbox[ $id ] ) ) return 0;
            $row = $this->inbox[ $id ];
            if ( $row['owner_token'] !== $owner_token || $row['status'] !== $req_status ) return 0;
            $this->inbox[ $id ]['status']           = $status;
            $this->inbox[ $id ]['response_payload'] = $payload;
            $this->inbox[ $id ]['http_status']      = $http_status;
            $this->inbox[ $id ]['lease_expires_at'] = null;
            $this->inbox[ $id ]['owner_token']      = null;
            $this->inbox[ $id ]['last_error']       = null;
            $this->inbox[ $id ]['updated_at']       = $now;
            return 1;
        }

        // 5) sa_whatsapp_inbox fail()
        if ( false !== strpos( $sql, "UPDATE {$this->prefix}sa_whatsapp_inbox" ) && false !== strpos( $sql, 'last_error = %s' ) ) {
            list( $status, $payload, $http_status, $err, $now, $id, $owner_token, $req_status ) = $args;
            if ( ! isset( $this->inbox[ $id ] ) ) return 0;
            $row = $this->inbox[ $id ];
            if ( $row['owner_token'] !== $owner_token || $row['status'] !== $req_status ) return 0;
            $this->inbox[ $id ]['status']           = $status;
            $this->inbox[ $id ]['response_payload'] = $payload;
            $this->inbox[ $id ]['http_status']      = $http_status;
            $this->inbox[ $id ]['lease_expires_at'] = null;
            $this->inbox[ $id ]['owner_token']      = null;
            $this->inbox[ $id ]['last_error']       = $err;
            $this->inbox[ $id ]['updated_at']       = $now;
            return 1;
        }

        // 6) sa_whatsapp_inbox DELETE old records
        if ( false !== strpos( $sql, "DELETE FROM {$this->prefix}sa_whatsapp_inbox" ) ) {
            $statuses = array_slice( $args, 0, 3 );
            $cutoff   = $args[3];
            $deleted  = 0;
            foreach ( $this->inbox as $id => $row ) {
                if ( in_array( $row['status'], $statuses, true ) && $row['updated_at'] < $cutoff ) {
                    unset( $this->inbox[ $id ] );
                    $deleted++;
                }
            }
            return $deleted;
        }

        // 7) sa_action_idempotency INSERT IGNORE
        if ( false !== strpos( $sql, "INSERT IGNORE INTO {$this->prefix}sa_action_idempotency" ) ) {
            list( $scope, $key, $actor_hash, $owner_token, $status, $now, $now2, $lease_until ) = $args;
            foreach ( $this->actions as $row ) {
                if ( $row['scope'] === $scope && $row['idempotency_key'] === $key ) {
                    $this->insert_id = 0;
                    return 0;
                }
            }
            $id = $this->next_action_id++;
            $this->actions[ $id ] = [
                'id'               => $id,
                'scope'            => $scope,
                'idempotency_key'  => $key,
                'actor_hash'       => $actor_hash,
                'owner_token'      => $owner_token,
                'status'           => $status,
                'response_payload' => null,
                'http_status'      => 200,
                'created_at'       => $now,
                'updated_at'       => $now2,
                'lease_expires_at' => $lease_until,
                'attempt_count'    => 1,
                'last_error'       => null,
            ];
            $this->insert_id = $id;
            return 1;
        }

        // 8) sa_action_idempotency complete_action / DELETE
        if ( false !== strpos( $sql, "UPDATE {$this->prefix}sa_action_idempotency" ) && false !== strpos( $sql, 'response_payload = %s' ) && false === strpos( $sql, 'last_error = %s' ) ) {
            list( $status, $payload, $http_status, $now, $id, $owner_token, $req_status ) = $args;
            if ( ! isset( $this->actions[ $id ] ) ) return 0;
            $row = $this->actions[ $id ];
            if ( $row['owner_token'] !== $owner_token || $row['status'] !== $req_status ) return 0;
            $this->actions[ $id ]['status']           = $status;
            $this->actions[ $id ]['response_payload'] = $payload;
            $this->actions[ $id ]['http_status']      = $http_status;
            $this->actions[ $id ]['lease_expires_at'] = null;
            $this->actions[ $id ]['owner_token']      = null;
            $this->actions[ $id ]['updated_at']       = $now;
            return 1;
        }
        if ( false !== strpos( $sql, "DELETE FROM {$this->prefix}sa_action_idempotency" ) ) {
            $statuses = array_slice( $args, 0, 3 );
            $cutoff   = $args[3];
            $deleted  = 0;
            foreach ( $this->actions as $id => $row ) {
                if ( in_array( $row['status'], $statuses, true ) && $row['updated_at'] < $cutoff ) {
                    unset( $this->actions[ $id ] );
                    $deleted++;
                }
            }
            return $deleted;
        }

        return 0;
    }

    public function get_row( $prepared, $output = ARRAY_A ) {
        if ( $this->simulate_db_failure ) return null;
        $sql  = is_array( $prepared ) ? $prepared['sql'] : (string) $prepared;
        $args = is_array( $prepared ) ? $prepared['args'] : [];

        if ( false !== strpos( $sql, "FROM {$this->prefix}sa_whatsapp_inbox WHERE provider = %s AND message_id = %s" ) ) {
            foreach ( $this->inbox as $row ) {
                if ( $row['provider'] === $args[0] && $row['message_id'] === $args[1] ) return $row;
            }
            return null;
        }
        if ( false !== strpos( $sql, "FROM {$this->prefix}sa_whatsapp_inbox WHERE id = %d" ) ) {
            return $this->inbox[ (int) $args[0] ] ?? null;
        }
        if ( false !== strpos( $sql, "FROM {$this->prefix}sa_action_idempotency WHERE scope = %s AND idempotency_key = %s" ) ) {
            foreach ( $this->actions as $row ) {
                if ( $row['scope'] === $args[0] && $row['idempotency_key'] === $args[1] ) return $row;
            }
            return null;
        }
        return null;
    }

    public function get_var( $prepared ) {
        global $sa_test_options, $sa_test_user_meta;
        if ( $this->simulate_db_failure ) return null;
        $sql  = is_array( $prepared ) ? $prepared['sql'] : (string) $prepared;
        $args = is_array( $prepared ) ? $prepared['args'] : [];

        if ( false !== strpos( $sql, "FROM {$this->options} WHERE option_name = %s" ) ) {
            return $sa_test_options[ (string) $args[0] ] ?? null;
        }
        if ( false !== strpos( $sql, "FROM {$this->usermeta}" ) ) {
            $candidates = array_map( function( $c ) {
                return str_replace( [ '+', ' ', '-', '(', ')' ], '', (string) $c );
            }, $args );
            foreach ( $sa_test_user_meta as $uid => $metas ) {
                foreach ( [ '_vmp_phone_normalized', 'billing_phone' ] as $mk ) {
                    if ( ! empty( $metas[ $mk ] ) ) {
                        $norm = str_replace( [ '+', ' ', '-', '(', ')' ], '', (string) $metas[ $mk ] );
                        if ( in_array( $norm, $candidates, true ) ) {
                            return $uid;
                        }
                    }
                }
            }
            return null;
        }
        if ( false !== strpos( $sql, 'SHOW TABLES LIKE' ) ) {
            return $args[0];
        }
        return null;
    }
}

$wpdb = new SA_Mock_WPDB();
$sa_test_options['sa_customer_db_version'] = '1.5.0';

// Minimal stubs for analyzer & search to count real executions
$sa_test_analyzer_calls = 0;
$sa_test_analyzer_mode  = 'ok';
$sa_test_reg_creates    = 0;

class Service_Conversation_Analyzer {
    public static function analyze( $message, $history = [], $known_context = [], $state = [] ) {
        global $sa_test_analyzer_calls, $sa_test_analyzer_mode;
        $sa_test_analyzer_calls++;
        if ( 'error' === $sa_test_analyzer_mode ) {
            return [ 'error' => 'upstream_timeout' ];
        }
        return [
            'action'     => 'chitchat',
            'reply_text' => 'أهلاً! وصلت رسالتك: ' . $message,
            'type'       => null,
            'known'      => [],
        ];
    }
    public static function recover_type_from_history( $h ) { return null; }
}

class SA_Customer_Service_Settings {
    public static function get_config() {
        return [
            'enabled' => 1,
            'skills'  => [ 'product_search' => 1, 'offers_discounts' => 1 ],
        ];
    }
}

class SA_WhatsApp_Settings {
    public static function get_config() {
        return [
            'enabled'             => 1,
            'integration_enabled' => 1,
            'knowledge_enabled'   => 1,
            'memory_sync_enabled' => 1,
            'strict_sender_phone' => 0,
            'inbox_retention_days'=> 14,
            'memory_minutes'      => 30,
            'max_history'         => 4,
            'reply_mode'          => 'single_link',
            'bot_name'            => 'مساعد المتجر',
            'skills'              => [ 'offers' => 1, 'greeting' => 1, 'product_search' => 1 ],
            'reply_templates'     => [],
        ];
    }
}

require_once ABSPATH . 'services/class-whatsapp-inbox.php';
require_once ABSPATH . 'services/class-whatsapp-magic-link.php';
require_once ABSPATH . 'services/class-whatsapp-registration.php';
require_once ABSPATH . 'services/class-whatsapp-reply.php';

// Preconfigure API key hash
$api_key = 'sa_test_secret_653';
$sa_test_options[ SA_WhatsApp_Reply::OPTION_KEY_HASH ] = hash_hmac( 'sha256', $api_key, wp_salt( 'auth' ) );

$results = [];
function sa_assert( $name, $condition, $detail = '' ) {
    global $results;
    $results[] = [ 'name' => $name, 'ok' => (bool) $condition, 'detail' => $detail ];
}

// 1. Repeated message_id after COMPLETED -> replay without side effects
$sa_test_analyzer_calls = 0;
$req1 = new WP_REST_Request(
    [ 'sender' => '967777111222', 'phone' => '967777111222', 'message' => 'مرحبا', 'message_id' => 'msg-replay-001' ],
    [ 'X-SA-API-Key' => $api_key ]
);
$res1a = SA_WhatsApp_Reply::handle( $req1 );
$res1b = SA_WhatsApp_Reply::handle( $req1 );
sa_assert(
    '1. Completed duplicate message_id returns cached reply with Idempotency-Replayed header & analyzer called once',
    200 === $res1a->get_status()
    && 200 === $res1b->get_status()
    && $res1a->get_data() === $res1b->get_data()
    && 'true' === ( $res1b->get_headers()['Idempotency-Supported'] ?? '' )
    && 'true' === ( $res1b->get_headers()['Idempotency-Replayed'] ?? '' )
    && 1 === $sa_test_analyzer_calls
);

// 2. Concurrent arrival while PROCESSING -> 409 Conflict + Retry-After
$acq_conc = SA_WhatsApp_Inbox::acquire( 'automate_ai', 'msg-concurrent-002', SA_WhatsApp_Inbox::hash_value( '967777111222' ), 60 );
$req2 = new WP_REST_Request(
    [ 'sender' => '967777111222', 'message' => 'مرحبا', 'message_id' => 'msg-concurrent-002', 'provider' => 'automate_ai' ],
    [ 'X-SA-API-Key' => $api_key ]
);
$res2 = SA_WhatsApp_Reply::handle( $req2 );
sa_assert(
    '2. Concurrent in-flight message returns HTTP 409 + Retry-After',
    'acquired' === $acq_conc['outcome']
    && 409 === $res2->get_status()
    && ! empty( $res2->get_headers()['Retry-After'] )
);

// 3. Stale expired PROCESSING lease recovery -> atomic takeover wins once
$wpdb->inbox[ $acq_conc['id'] ]['lease_expires_at'] = gmdate( 'Y-m-d H:i:s', time() - 120 );
$acq_takeover = SA_WhatsApp_Inbox::acquire( 'automate_ai', 'msg-concurrent-002', SA_WhatsApp_Inbox::hash_value( '967777111222' ), 60 );
sa_assert(
    '3. Expired PROCESSING lease recovered atomically with incremented attempt_count',
    'acquired' === $acq_takeover['outcome']
    && true === $acq_takeover['reclaimed']
    && 2 === (int) $acq_takeover['attempt_count']
);

// 4. Owner-token fencing prevents stale worker from overwriting reclaimed row
$stale_complete_ok = SA_WhatsApp_Inbox::complete( $acq_conc['id'], $acq_conc['owner_token'], [ 'reply' => 'stale' ], 200 );
$fresh_complete_ok = SA_WhatsApp_Inbox::complete( $acq_takeover['id'], $acq_takeover['owner_token'], [ 'reply' => 'fresh' ], 200 );
sa_assert(
    '4. Owner-token fencing blocks stale worker complete() and allows current owner complete()',
    false === $stale_complete_ok && true === $fresh_complete_ok
);

// 5. Temporary error -> RETRYABLE (503), subsequent retry succeeds & completes
$sa_test_analyzer_mode = 'error';
$req5 = new WP_REST_Request(
    [ 'sender' => '967777111222', 'message' => 'عطور', 'message_id' => 'msg-retryable-005' ],
    [ 'X-SA-API-Key' => $api_key ]
);
$res5_fail = SA_WhatsApp_Reply::handle( $req5 );
$sa_test_analyzer_mode = 'ok';
$res5_ok = SA_WhatsApp_Reply::handle( $req5 );
$res5_replay = SA_WhatsApp_Reply::handle( $req5 );
sa_assert(
    '5. Temporary analyzer failure marks RETRYABLE (503); retry completes and replays cleanly',
    503 === $res5_fail->get_status()
    && 200 === $res5_ok->get_status()
    && 'true' === ( $res5_replay->get_headers()['Idempotency-Replayed'] ?? '' )
);

// 6. Magic link retry returns exact same URL from cache without invalidating/regenerating token
$uid_active = wp_create_user( 'wa_777555444', 'Pass1234!', '777555444@example.test' );
update_user_meta( $uid_active, '_vmp_phone_normalized', '967777555444' );
$req6 = new WP_REST_Request(
    [ 'sender' => '967777555444', 'phone' => '967777555444', 'message' => 'اربط حسابي 777555444', 'message_id' => 'msg-ml-006' ],
    [ 'X-SA-API-Key' => $api_key ]
);
$res6a = SA_WhatsApp_Reply::handle( $req6 );
$hash_after_first = get_user_meta( $uid_active, SA_WhatsApp_Magic_Link::META_TOKEN_HASH, true );
$res6b = SA_WhatsApp_Reply::handle( $req6 );
$hash_after_retry = get_user_meta( $uid_active, SA_WhatsApp_Magic_Link::META_TOKEN_HASH, true );
sa_assert(
    '6. Magic link duplicate delivery returns identical link & preserves first active token hash',
    200 === $res6a->get_status()
    && $res6a->get_data()['reply'] === $res6b->get_data()['reply']
    && '' !== $hash_after_first
    && hash_equals( $hash_after_first, $hash_after_retry )
    && false !== strpos( $res6a->get_data()['reply'], 'sa_ml_token=' )
);

// 7. Pending registration user cannot bypass activation via magic link
$uid_pending = wp_create_user( 'wa_777888999', 'TmpPass123!', '777888999@whatsapp.local' );
update_user_meta( $uid_pending, '_vmp_phone_normalized', '967777888999' );
update_user_meta( $uid_pending, SA_WhatsApp_Registration::META_PENDING, 1 );
sa_assert(
    '7. Pending account blocked from magic login eligibility and token generation',
    false === SA_WhatsApp_Magic_Link::is_user_eligible( $uid_pending )
    && '' === SA_WhatsApp_Magic_Link::generate_token( $uid_pending )
);

// 8. Registration retry with same message_id returns initial success payload instead of "registered already"
$reg_before = $sa_test_reg_creates;
$req8 = new WP_REST_Request(
    [ 'sender' => '967777333222', 'phone' => '967777333222', 'message' => 'تسجيل 777333222', 'message_id' => 'msg-reg-008' ],
    [ 'X-SA-API-Key' => $api_key ]
);
$res8a = SA_WhatsApp_Reply::handle( $req8 );
$res8b = SA_WhatsApp_Reply::handle( $req8 );
sa_assert(
    '8. Registration retry returns original activation reply without creating second user or switching to already-exists message',
    1 === ( $sa_test_reg_creates - $reg_before )
    && $res8a->get_data()['reply'] === $res8b->get_data()['reply']
    && false !== strpos( $res8b->get_data()['reply'], 'sa_activate=' )
    && 'true' === ( $res8b->get_headers()['Idempotency-Replayed'] ?? '' )
);

// 9. Distinct new messages from same sender with identical text ("نعم") BOTH execute
$sa_test_analyzer_calls = 0;
$res9a = SA_WhatsApp_Reply::handle( new WP_REST_Request(
    [ 'sender' => '967777111222', 'message' => 'نعم', 'message_id' => 'msg-yes-9a' ],
    [ 'X-SA-API-Key' => $api_key ]
) );
$res9b = SA_WhatsApp_Reply::handle( new WP_REST_Request(
    [ 'sender' => '967777111222', 'message' => 'نعم', 'message_id' => 'msg-yes-9b' ],
    [ 'X-SA-API-Key' => $api_key ]
) );
sa_assert(
    '9. Distinct message_ids with identical text ("نعم") from same sender both process',
    2 === $sa_test_analyzer_calls
    && empty( $res9b->get_headers()['Idempotency-Replayed'] )
);

// 10. Missing message_id does NOT drop repeated "نعم" messages and tags header
$sa_test_analyzer_calls = 0;
$req10 = new WP_REST_Request(
    [ 'sender' => '967777111222', 'message' => 'نعم' ],
    [ 'X-SA-API-Key' => $api_key ]
);
$res10a = SA_WhatsApp_Reply::handle( $req10 );
$res10b = SA_WhatsApp_Reply::handle( $req10 );
sa_assert(
    '10. Missing message_id never drops messages based on text similarity and emits X-SA-Message-Id-Status: missing',
    2 === $sa_test_analyzer_calls
    && 'missing' === ( $res10a->get_headers()['X-SA-Message-Id-Status'] ?? '' )
    && 'missing' === ( $res10b->get_headers()['X-SA-Message-Id-Status'] ?? '' )
);

// 11. Gateway identity extraction (Meta Cloud, Twilio, Evolution, WAHA, AutoMate/X-Message-ID, overlong hash)
$meta_req = new WP_REST_Request( [], [], [
    'object' => 'whatsapp_business_account',
    'entry'  => [ [ 'changes' => [ [ 'value' => [
        'contacts' => [ [ 'wa_id' => '967777000111' ] ],
        'messages' => [ [ 'id' => 'wamid.HBgNOTY3Nzc3MDAwMTEx', 'from' => '967777000111' ] ],
    ] ] ] ] ],
] );
$twilio_req = new WP_REST_Request( [ 'SmsMessageSid' => 'SM12345', 'From' => 'whatsapp:+967777000111' ] );
$evo_req    = new WP_REST_Request( [], [], [ 'key' => [ 'id' => '3EB0ABC', 'remoteJid' => '967777000111@s.whatsapp.net' ] ] );
$waha_req   = new WP_REST_Request( [], [], [ 'event' => 'message', 'payload' => [ 'id' => 'false_967777@c.us_XYZ', 'from' => '967777000111@c.us' ] ] );
$hdr_req    = new WP_REST_Request( [ 'sender' => 'Ahmed' ], [ 'X-Message-ID' => str_repeat( 'x', 240 ) ] );

$id_meta   = SA_WhatsApp_Inbox::extract_identity( $meta_req );
$id_twilio = SA_WhatsApp_Inbox::extract_identity( $twilio_req );
$id_evo    = SA_WhatsApp_Inbox::extract_identity( $evo_req );
$id_waha   = SA_WhatsApp_Inbox::extract_identity( $waha_req );
$id_hdr    = SA_WhatsApp_Inbox::extract_identity( $hdr_req );

sa_assert(
    '11. Extensible gateway identity extraction handles Meta, Twilio, Evolution, WAHA & hashes >191-char headers',
    'whatsapp_cloud' === $id_meta['provider'] && 'wamid.HBgNOTY3Nzc3MDAwMTEx' === $id_meta['message_id']
    && 'twilio' === $id_twilio['provider'] && 'SM12345' === $id_twilio['message_id']
    && 'evolution' === $id_evo['provider'] && '3EB0ABC' === $id_evo['message_id']
    && 'waha' === $id_waha['provider'] && 'false_967777@c.us_XYZ' === $id_waha['message_id']
    && 0 === strpos( $id_hdr['message_id'], 'sha256:' ) && strlen( $id_hdr['message_id'] ) <= 191
);

// 12. Sender phone ownership & group blocking
$mismatch = SA_WhatsApp_Inbox::verify_sender_phone_ownership(
    new WP_REST_Request( [ 'sender' => '967777111222', 'phone' => '967777111222' ] ),
    '967777999888',
    [ 'strict_sender_phone' => 0 ]
);
$group_check = SA_WhatsApp_Inbox::verify_sender_phone_ownership(
    new WP_REST_Request( [ 'sender' => '967777999888', 'group_name' => 'VIP Group' ] ),
    '967777999888',
    [ 'strict_sender_phone' => 0 ]
);
sa_assert(
    '12. Sender phone mismatch and group chats are blocked from personal account actions',
    false === $mismatch['allowed'] && 'sender_phone_mismatch' === $mismatch['reason']
    && false === $group_check['allowed'] && 'group_not_allowed' === $group_check['reason']
);

// 13. Rate-limiter Fail-Closed on DB error + cached replay does not consume quota
$wpdb->simulate_db_failure = true;
$rl_fail_closed = SA_WhatsApp_Magic_Link::check_rate_limit( 'fail_test_sender' );
$global_rl_err  = SA_WhatsApp_Reply::enforce_global_rate_limit();
$wpdb->simulate_db_failure = false;

$sa_test_options[ '_transient_sa_wa_rl_' . (int) floor( time() / 60 ) ] = (string) SA_WhatsApp_Reply::MAX_PER_MINUTE;
$res13_replay = SA_WhatsApp_Reply::handle( $req1 ); // msg-replay-001 already cached
sa_assert(
    '13. Rate limiter fails closed on DB errors & cached replay bypasses rate-limit consumption',
    false === $rl_fail_closed
    && is_wp_error( $global_rl_err )
    && 200 === $res13_replay->get_status()
    && 'true' === ( $res13_replay->get_headers()['Idempotency-Replayed'] ?? '' )
);

// 14. Retention cleanup deletes expired COMPLETED records while preserving recent ones
$old_acq = SA_WhatsApp_Inbox::acquire( 'generic', 'msg-old-14', 'hash14', 60 );
SA_WhatsApp_Inbox::complete( $old_acq['id'], $old_acq['owner_token'], [ 'reply' => 'old' ], 200 );
$wpdb->inbox[ $old_acq['id'] ]['updated_at'] = gmdate( 'Y-m-d H:i:s', time() - ( 20 * 86400 ) );
SA_WhatsApp_Inbox::purge_old_records( 14 );
sa_assert(
    '14. purge_old_records removes rows older than retention window while keeping recent rows',
    ! isset( $wpdb->inbox[ $old_acq['id'] ] ) && count( $wpdb->inbox ) >= 1
);

// Print results summary
$failed = 0;
foreach ( $results as $r ) {
    echo ( $r['ok'] ? '[PASS] ' : '[FAIL] ' ) . $r['name'] . ( '' !== $r['detail'] ? ' — ' . $r['detail'] : '' ) . "\n";
    if ( ! $r['ok'] ) $failed++;
}
echo "\nTotal: " . count( $results ) . " assertions, Failed: {$failed}\n";
exit( $failed > 0 ? 1 : 0 );

<?php
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_WhatsApp_Reply {

    const OPTION_KEY_HASH = 'sa_whatsapp_api_key_hash';
    const MAX_PER_MINUTE  = 60;

    private static $cached_config = null;

    public static function init() {
        add_action( 'rest_api_init', [ __CLASS__, 'register_routes' ] );
    }

    public static function register_routes() {
        register_rest_route( 'smart-advisor/v1', '/whatsapp/reply', [
            'methods'             => 'POST',
            'callback'            => [ __CLASS__, 'handle' ],
            'permission_callback' => [ __CLASS__, 'permission' ],
        ] );
    }

    public static function reset_request_cache() {
        self::$cached_config = null;
    }

    private static function get_config() {
        if ( null !== self::$cached_config ) {
            return self::$cached_config;
        }
        self::$cached_config = class_exists( 'SA_WhatsApp_Settings' ) ? SA_WhatsApp_Settings::get_config() : [];
        return self::$cached_config;
    }

    private static function global_skill_enabled( $skill ) {
        if ( ! class_exists( 'SA_Customer_Service_Settings' ) ) return true;
        $settings = SA_Customer_Service_Settings::get_config();
        return ! empty( $settings['enabled'] ) && ! empty( $settings['skills'][ $skill ] );
    }

    /**
     * Authentication & availability check ONLY.
     * Rate-limiting runs AFTER Inbox idempotency check so cached replays never consume quota.
     */
    public static function permission( $request ) {
        self::reset_request_cache();
        $config = self::get_config();
        if ( empty( $config['enabled'] ) ) {
            return new WP_Error( 'sa_wa_disabled', 'الخدمة معطلة.', [ 'status' => 403 ] );
        }
        if ( class_exists( 'SA_Customer_Service_Settings' ) ) {
            $service_config = SA_Customer_Service_Settings::get_config();
            if ( empty( $service_config['enabled'] ) ) {
                return new WP_Error( 'sa_customer_service_disabled', 'المساعد وخدمة العملاء معطلان حالياً.', [ 'status' => 403 ] );
            }
        }

        $provided = method_exists( $request, 'get_header' ) ? $request->get_header( 'X-SA-API-Key' ) : '';
        if ( empty( $provided ) || ! is_string( $provided ) ) {
            return new WP_Error( 'sa_missing_key', 'مفتاح API مطلوب.', [ 'status' => 401 ] );
        }

        $stored = (string) get_option( self::OPTION_KEY_HASH, '' );
        $secret = defined( 'AUTH_KEY' ) ? AUTH_KEY : ( function_exists( 'wp_salt' ) ? wp_salt( 'auth' ) : 'sa_auth_salt' );

        if ( '' === $stored || ! hash_equals( $stored, hash_hmac( 'sha256', $provided, $secret ) ) ) {
            return new WP_Error( 'sa_bad_key', 'مفتاح API غير صحيح.', [ 'status' => 403 ] );
        }

        return true;
    }

    /**
     * Atomic DB global per-minute rate limiter (Fail-Closed on DB error).
     * Probabilistic cleanup runs 1 in 50 requests instead of every request.
     *
     * @return true|WP_Error
     */
    public static function enforce_global_rate_limit( $max_per_minute = self::MAX_PER_MINUTE ) {
        global $wpdb;
        $window = (int) floor( time() / 60 );
        $key = 'sa_wa_rl_' . $window;
        $value_name = '_transient_' . $key;
        $timeout_name = '_transient_timeout_' . $key;

        $saved = $wpdb->query( $wpdb->prepare(
            "INSERT INTO {$wpdb->options} (option_name, option_value, autoload) VALUES (%s, %s, 'no')
             ON DUPLICATE KEY UPDATE option_value = CAST(option_value AS UNSIGNED) + 1",
            $value_name,
            '1'
        ) );
        if ( false === $saved ) {
            return new WP_Error( 'sa_rl_error', 'فشل التحقق من حد الطلبات.', [ 'status' => 503 ] );
        }

        $timeout_saved = $wpdb->query( $wpdb->prepare(
            "INSERT IGNORE INTO {$wpdb->options} (option_name, option_value, autoload) VALUES (%s, %s, 'no')",
            $timeout_name,
            (string) ( ( $window + 2 ) * 60 )
        ) );
        if ( false === $timeout_saved ) {
            return new WP_Error( 'sa_rl_error', 'فشل ضبط انتهاء عداد الطلبات.', [ 'status' => 503 ] );
        }

        if ( mt_rand( 1, 50 ) === 1 ) {
            $now = time();
            $wpdb->query( $wpdb->prepare(
                "DELETE counter FROM {$wpdb->options} counter
                 INNER JOIN {$wpdb->options} expiry
                    ON expiry.option_name = CONCAT('_transient_timeout_', SUBSTRING(counter.option_name, 12))
                 WHERE counter.option_name LIKE '_transient_sa_wa_rl_%' AND CAST(expiry.option_value AS UNSIGNED) < %d",
                $now
            ) );
            $wpdb->query( $wpdb->prepare(
                "DELETE FROM {$wpdb->options} WHERE option_name LIKE '_transient_timeout_sa_wa_rl_%' AND CAST(option_value AS UNSIGNED) < %d",
                $now
            ) );
        }

        $count = $wpdb->get_var( $wpdb->prepare(
            "SELECT option_value FROM {$wpdb->options} WHERE option_name = %s LIMIT 1",
            $value_name
        ) );
        if ( null === $count || false === $count ) {
            return new WP_Error( 'sa_rl_error', 'تعذر قراءة عداد الطلبات.', [ 'status' => 503 ] );
        }

        if ( (int) $count > (int) $max_per_minute ) {
            return new WP_Error( 'sa_rate', 'تجاوز الحد المسموح للطلبات.', [ 'status' => 429 ] );
        }
        return true;
    }

    private static function render_template( $key, $variables = [], $fallback = '' ) {
        $config = self::get_config();
        $templates = is_array( $config['reply_templates'] ?? null ) ? $config['reply_templates'] : [];
        $template = isset( $templates[ $key ] ) && is_string( $templates[ $key ] ) ? $templates[ $key ] : $fallback;
        if ( '' === trim( $template ) ) $template = $fallback;
        $defaults = [
            '{store_name}' => function_exists( 'get_bloginfo' ) ? get_bloginfo( 'name' ) : '',
            '{bot_name}'   => sanitize_text_field( $config['bot_name'] ?? 'مساعد المتجر' ),
            '{link}'       => '',
            '{phone}'      => '',
            '{error}'      => '',
        ];
        foreach ( $variables as $name => $value ) {
            $placeholder = '{' . trim( (string) $name, '{}' ) . '}';
            if ( array_key_exists( $placeholder, $defaults ) ) $defaults[ $placeholder ] = (string) $value;
        }
        return strtr( $template, $defaults );
    }

    private static function build_rest_response( $payload, $status = 200, $replayed = false, $extra_headers = [] ) {
        $resp = new WP_REST_Response( $payload, $status );
        $resp->header( 'Idempotency-Supported', 'true' );
        if ( $replayed ) {
            $resp->header( 'Idempotency-Replayed', 'true' );
        }
        foreach ( $extra_headers as $h_name => $h_val ) {
            $resp->header( $h_name, (string) $h_val );
        }
        return $resp;
    }

    private static function response( $reply, $status = 200, $extra_headers = [] ) {
        return self::build_rest_response(
            [ 'reply' => self::format_for_whatsapp( $reply ) ],
            $status,
            false,
            $extra_headers
        );
    }

    public static function handle( $request ) {
        $config  = self::get_config();
        $message = $request->get_param( 'message' );
        $sender  = $request->get_param( 'sender' );

        if ( ! is_string( $sender ) || trim( $sender ) === '' ) {
            return self::build_rest_response( [ 'reply' => 'خطأ: لم يتم التعرف على رقم المرسل.' ], 400 );
        }
        $sender = preg_replace( '/[^\p{L}\p{N}_+@.\-]/u', '', trim( $sender ) );
        if ( '' === $sender || strlen( $sender ) > 128 ) {
            return self::build_rest_response( [ 'reply' => 'تعذر التحقق من هوية المرسل.' ], 400 );
        }

        if ( ! is_string( $message ) || trim( $message ) === '' ) {
            return self::response( 'كيف يمكنني مساعدتك؟', 200 );
        }

        if ( function_exists( 'mb_strlen' ) && mb_strlen( $message, 'UTF-8' ) > 500 ) {
            $message = mb_substr( $message, 0, 500, 'UTF-8' );
        }

        // === DELIVERY_UNKNOWN GUARD (Prevent duplicate/blind re-processing on uncertain gateway ack) ===
        $delivery_status = strtoupper( trim( (string) (
            $request->get_param( 'delivery_status' )
            ?: ( method_exists( $request, 'get_header' ) ? $request->get_header( 'X-SA-Delivery-Status' ) : '' )
        ) ) );
        if ( 'DELIVERY_UNKNOWN' === $delivery_status ) {
            return self::build_rest_response( [
                'reply'           => '',
                'intent'          => 'DELIVERY_UNKNOWN',
                'status'          => 'DELIVERY_UNKNOWN',
                'skip_resend'     => true,
                'delivery_unknown'=> true,
                'context_updated' => false,
            ], 200 );
        }

        // === CENTRAL INBOX IDEMPOTENCY ===
        $identity = class_exists( 'SA_WhatsApp_Inbox' )
            ? SA_WhatsApp_Inbox::extract_identity( $request )
            : [ 'provider' => 'generic', 'message_id' => '', 'has_message_id' => false, 'sender_hash' => '', 'is_group' => false, 'retry_token' => '' ];

        $inbox_key = $identity['has_message_id']
            ? $identity['message_id']
            : ( ! empty( $identity['retry_token'] ) ? 'delivery:' . $identity['retry_token'] : '' );

        if ( ! $identity['has_message_id'] && class_exists( 'SA_WhatsApp_Inbox' ) ) {
            SA_WhatsApp_Inbox::record_missing_message_id( $identity['provider'], $identity['sender_hash'] );
        }

        $lock_id = 0;
        $owner_token = '';
        $lock_closed = false;

        if ( '' !== $inbox_key && class_exists( 'SA_WhatsApp_Inbox' ) ) {
            $acq = SA_WhatsApp_Inbox::acquire( $identity['provider'], $inbox_key, $identity['sender_hash'] );
            if ( 'replay' === $acq['outcome'] || 'failed_permanent' === $acq['outcome'] ) {
                return SA_WhatsApp_Inbox::replay_response_from_row( $acq['row'] );
            }
            if ( 'in_flight' === $acq['outcome'] ) {
                return self::build_rest_response(
                    [ 'error' => 'الرسالة قيد المعالجة حالياً.', 'status' => 'processing' ],
                    409,
                    false,
                    [ 'Retry-After' => (int) ( $acq['retry_after'] ?? 5 ) ]
                );
            }
            if ( 'db_error' === $acq['outcome'] ) {
                return self::build_rest_response(
                    [ 'reply' => 'الخدمة غير متاحة مؤقتاً.', 'error' => 'inbox_unavailable' ],
                    503,
                    false,
                    [ 'Retry-After' => 10 ]
                );
            }
            if ( 'acquired' === $acq['outcome'] ) {
                $lock_id     = (int) $acq['id'];
                $owner_token = (string) $acq['owner_token'];
            }
        }

        $missing_id_header = $identity['has_message_id'] ? [] : [ 'X-SA-Message-Id-Status' => 'missing' ];

        $finish_complete = function( $payload, $status = 200 ) use ( &$lock_closed, $lock_id, $owner_token, $missing_id_header ) {
            $lock_closed = true;
            if ( $lock_id > 0 && '' !== $owner_token && class_exists( 'SA_WhatsApp_Inbox' ) ) {
                SA_WhatsApp_Inbox::complete( $lock_id, $owner_token, $payload, $status );
            }
            return self::build_rest_response( $payload, $status, false, $missing_id_header );
        };

        $finish_fail = function( $inbox_status, $error_msg, $payload, $http_status = 503, $extra_headers = [] ) use ( &$lock_closed, $lock_id, $owner_token, $missing_id_header ) {
            $lock_closed = true;
            if ( $lock_id > 0 && '' !== $owner_token && class_exists( 'SA_WhatsApp_Inbox' ) ) {
                SA_WhatsApp_Inbox::fail( $lock_id, $owner_token, $inbox_status, $error_msg, $payload, $http_status );
            }
            return self::build_rest_response( $payload, $http_status, false, array_merge( $missing_id_header, $extra_headers ) );
        };

        if ( $lock_id > 0 && '' !== $owner_token && function_exists( 'register_shutdown_function' ) ) {
            register_shutdown_function( function() use ( &$lock_closed, $lock_id, $owner_token ) {
                if ( ! $lock_closed && class_exists( 'SA_WhatsApp_Inbox' ) ) {
                    SA_WhatsApp_Inbox::fail( $lock_id, $owner_token, SA_WhatsApp_Inbox::STATUS_RETRYABLE, 'unexpected_shutdown', null, 503 );
                }
            } );
        }

        try {
            // Global rate limit runs ONLY for newly acquired work (never penalizes cached replays).
            $rl = self::enforce_global_rate_limit();
            if ( is_wp_error( $rl ) ) {
                $err_data = $rl->get_error_data();
                $err_status = is_array( $err_data ) && ! empty( $err_data['status'] ) ? (int) $err_data['status'] : 503;
                return $finish_fail(
                    'RETRYABLE',
                    $rl->get_error_message(),
                    [ 'error' => $rl->get_error_message() ],
                    $err_status,
                    [ 'Retry-After' => 60 ]
                );
            }

            if ( ! class_exists( 'Service_Conversation_Analyzer' ) ) {
                $err_payload = [ 'reply' => self::format_for_whatsapp( self::render_template( 'service_error', [], 'الخدمة غير متاحة حالياً.' ) ) ];
                return $finish_fail( 'RETRYABLE', 'missing_analyzer', $err_payload, 503, [ 'Retry-After' => 15 ] );
            }

            // === REGISTRATION INTERCEPTION ===
            if ( class_exists( 'SA_WhatsApp_Registration' ) && SA_WhatsApp_Registration::is_register_request( $message ) ) {
                if ( ! empty( $identity['is_group'] ) ) {
                    return $finish_complete( [ 'reply' => 'لحماية خصوصية حسابك، لا يمكن تنفيذ طلبات التسجيل في المجموعات. تواصل معي في محادثة خاصة.' ], 200 );
                }

                $reg_phone = SA_WhatsApp_Registration::extract_phone( $message );
                if ( '' === $reg_phone ) {
                    return $finish_complete( [ 'reply' => 'يرجى إرسال كلمة تسجيل متبوعة برقم جوالك (مثال: تسجيل 777123456).' ], 200 );
                }

                if ( class_exists( 'SA_WhatsApp_Inbox' ) ) {
                    $ownership = SA_WhatsApp_Inbox::verify_sender_phone_ownership( $request, $reg_phone, $config );
                    if ( empty( $ownership['allowed'] ) ) {
                        return $finish_complete( [ 'reply' => 'تعذر التحقق من ملكية الرقم المرسل. استخدم الواتساب المرتبط بالرقم نفسه.' ], 200 );
                    }
                }

                if ( ! class_exists( 'SA_WhatsApp_Magic_Link' ) || ! SA_WhatsApp_Magic_Link::check_rate_limit( 'reg_' . $sender ) ) {
                    return $finish_complete( [ 'reply' => 'تجاوزت حد المحاولات. حاول بعد ساعة.' ], 200 );
                }

                list( $reg_uid, $reg_is_new, $reg_err ) = SA_WhatsApp_Registration::find_or_create( $reg_phone );

                if ( $reg_is_new && $reg_uid ) {
                    $setup_url = SA_WhatsApp_Registration::generate_setup_link( $reg_uid );
                    $reply = self::render_template( 'registration_success', [ 'link' => $setup_url ], "تم إنشاء حسابك بنجاح!\n\n{link}" );
                } elseif ( 'exists' === $reg_err && $reg_uid && SA_WhatsApp_Registration::is_pending( $reg_uid ) ) {
                    $setup_url = SA_WhatsApp_Registration::generate_setup_link( $reg_uid );
                    $reply = '' !== $setup_url
                        ? self::render_template( 'registration_success', [ 'link' => $setup_url ], "تم تجهيز رابط تفعيل حسابك:\n\n{link}" )
                        : self::render_template( 'registration_error', [ 'error' => 'تعذر تجهيز رابط التفعيل.' ], 'تعذر إكمال الطلب: {error}' );
                } elseif ( 'exists' === $reg_err ) {
                    $reply = self::render_template( 'registration_exists', [ 'phone' => $reg_phone ], "هذا الرقم مسجل مسبقاً.\nاستخدم: اربط حسابي {phone}" );
                } else {
                    $reply = self::render_template( 'registration_error', [ 'error' => $reg_err ], 'تعذر إنشاء الحساب: {error}' );
                }
                return $finish_complete( [ 'reply' => self::format_for_whatsapp( $reply ) ], 200 );
            }
            // === END REGISTRATION ===

            // === UNIFIED LOGIN/REGISTRATION INTERCEPTION ===
            if ( class_exists( 'SA_WhatsApp_Magic_Link' ) ) {
                $extracted_phone = '';
                $is_link_req = SA_WhatsApp_Magic_Link::is_link_request( $message )
                    || SA_WhatsApp_Magic_Link::is_phone_message( $message );
                if ( $is_link_req && preg_match( '/([0-9]{9,15})/', $message, $m ) ) {
                    $extracted_phone = $m[1];
                }

                if ( $is_link_req ) {
                    if ( ! empty( $identity['is_group'] ) ) {
                        return $finish_complete( [ 'reply' => 'لحماية خصوصية حسابك، لا يمكن إرسال روابط الدخول في المجموعات. راسلني في محادثة خاصة.' ], 200 );
                    }

                    if ( '' !== $extracted_phone ) {
                        if ( class_exists( 'SA_WhatsApp_Inbox' ) ) {
                            $ownership = SA_WhatsApp_Inbox::verify_sender_phone_ownership( $request, $extracted_phone, $config );
                            if ( empty( $ownership['allowed'] ) ) {
                                return $finish_complete( [ 'reply' => 'تعذر التحقق من ملكية الرقم المرسل. يرجى المراسلة من رقم الجوال نفسه.' ], 200 );
                            }
                        }

                        if ( ! SA_WhatsApp_Magic_Link::check_rate_limit( 'ml_' . $sender ) ) {
                            return $finish_complete( [ 'reply' => 'تجاوزت حد المحاولات. حاول بعد ساعة.' ], 200 );
                        }

                        $ml_user_id = SA_WhatsApp_Magic_Link::find_user_by_phone( $extracted_phone );

                        if ( $ml_user_id ) {
                            if ( class_exists( 'SA_WhatsApp_Registration' ) && SA_WhatsApp_Registration::is_pending( $ml_user_id ) ) {
                                // Pending account must finish password setup before magic login.
                                $setup_url = SA_WhatsApp_Registration::generate_setup_link( $ml_user_id );
                                $reply = '' !== $setup_url
                                    ? self::render_template( 'registration_success', [ 'link' => $setup_url ], "حسابك بانتظار التفعيل وتعيين كلمة المرور:\n\n{link}" )
                                    : 'تعذر تجهيز رابط التفعيل حالياً.';
                            } elseif ( SA_WhatsApp_Magic_Link::is_user_eligible( $ml_user_id ) ) {
                                $ml_url = SA_WhatsApp_Magic_Link::generate_token( $ml_user_id );
                                if ( '' !== $ml_url ) {
                                    $link_text = self::render_template( 'magic_link', [ 'link' => $ml_url ], "رابط الدخول إلى حسابك:\n{link}" );
                                    $reply = self::render_template( 'login_link_intro', [ 'phone' => $extracted_phone ], 'جاري ربط رقمك {phone} بحسابك...' ) . "\n\n" . $link_text;
                                } else {
                                    return $finish_fail( 'RETRYABLE', 'magic_link_save_failed', [ 'reply' => 'تعذر إنشاء رابط الدخول حالياً. حاول لاحقاً.' ], 503, [ 'Retry-After' => 10 ] );
                                }
                            } else {
                                $reply = 'تعذر إنشاء رابط دخول لهذا الحساب عبر واتساب.';
                            }
                        } else {
                            if ( class_exists( 'SA_WhatsApp_Registration' ) ) {
                                list( $new_uid, $is_new, $err ) = SA_WhatsApp_Registration::find_or_create( $extracted_phone );
                                if ( $is_new && $new_uid ) {
                                    $setup_url = SA_WhatsApp_Registration::generate_setup_link( $new_uid );
                                    $reply = self::render_template( 'new_account_intro', [ 'phone' => $extracted_phone ], 'جاري تسجيل حساب جديد على رقمك {phone}...' ) . "\n\n" . self::render_template( 'registration_success', [ 'link' => $setup_url ], "تم إنشاء حسابك بنجاح!\n{link}" );
                                } elseif ( 'exists' === $err && $new_uid && SA_WhatsApp_Registration::is_pending( $new_uid ) ) {
                                    $setup_url = SA_WhatsApp_Registration::generate_setup_link( $new_uid );
                                    $reply = self::render_template( 'registration_success', [ 'link' => $setup_url ], "تم تجهيز رابط تفعيل حسابك:\n\n{link}" );
                                } else {
                                    $reply = self::render_template( 'registration_error', [ 'error' => $err ], 'تعذر إنشاء الحساب: {error}' );
                                }
                            } else {
                                $reply = self::render_template( 'registration_error', [ 'error' => 'لم أجد حساباً مسجلاً بهذا الرقم.' ], 'تعذر إكمال الطلب: {error}' );
                            }
                        }
                        return $finish_complete( [ 'reply' => self::format_for_whatsapp( $reply ) ], 200 );
                    } else {
                        $link_msg = self::render_template( 'link_request', [], $config['link_request_message'] ?? 'لربط حسابك بالواتساب، أرسل رقم جوالك المسجل في المتجر.' );
                        return $finish_complete( [ 'reply' => self::format_for_whatsapp( $link_msg ) ], 200 );
                    }
                }
            }
            // === END MAGIC LINK ===

            // === ADDRESS FAST-PATH ===
            if ( preg_match( '/(?:عنوان(?:كم| المتجر)?|وين\s*المتجر|فين\s*المتجر|مكان\s*المتجر|موقع(?:كم| المتجر)?|الفرع|فروعكم|خريطة|خريطه)/iu', $message ) ) {
                $cs = class_exists( 'SA_Customer_Service_Settings' ) ? SA_Customer_Service_Settings::get_config() : [];
                $store_address = trim( (string) ( $cs['store_address'] ?? '' ) );
                if ( '' !== $store_address ) {
                    $lines = [ '📍 *عنوان المتجر*', '', $store_address ];
                    if ( ! empty( $cs['store_hours'] ) ) { $lines[] = ''; $lines[] = '🕒 ' . $cs['store_hours']; }
                    if ( ! empty( $cs['store_phone'] ) ) { $lines[] = ''; $lines[] = '📞 ' . $cs['store_phone']; }
                    if ( ! empty( $cs['store_map_url'] ) ) { $lines[] = ''; $lines[] = '🗺️ الموقع على الخريطة:'; $lines[] = $cs['store_map_url']; }
                    return $finish_complete( [ 'reply' => self::format_for_whatsapp( implode( "\n", $lines ) ) ], 200 );
                }
            }
            // === END ADDRESS FAST-PATH ===

            $secret = function_exists( 'wp_salt' ) ? wp_salt( 'auth' ) : ( defined( 'AUTH_KEY' ) ? AUTH_KEY : 'sa_ctx_salt' );
            $cache_key = 'sa_wa_ctx_' . hash_hmac( 'sha256', $sender, $secret );
            $context = get_transient( $cache_key );

            $history = [];
            $known_context = [];
            $conversation_state = [];
            $active_context = [];

            if ( is_array( $context ) && $config['memory_minutes'] > 0 ) {
                $history = is_array( $context['history'] ?? null ) ? $context['history'] : [];
                $known_context = is_array( $context['known_context'] ?? null ) ? $context['known_context'] : [];
                $conversation_state = is_array( $context['conversation_state'] ?? null ) ? $context['conversation_state'] : [];
                $active_context = is_array( $context['active_context'] ?? null ) ? $context['active_context'] : [];

                if ( empty( $known_context['type'] ) ) {
                    $legacy_type = Service_Conversation_Analyzer::recover_type_from_history( $history );
                    if ( null !== $legacy_type ) {
                        $known_context = [ 'type' => $legacy_type, 'known' => $known_context ];
                    }
                }
            }

            $orchestrator_mode = self::is_orchestrator_mode( $request );

            // === CATEGORY INTENT & WOOCOMMERCE TAXONOMY LINK PIPELINE ===
            if ( class_exists( 'SA_Category_Intent_Service' ) && SA_Category_Intent_Service::is_available() ) {
                $effective_config = $config;
                if ( empty( $effective_config['skills'] ) || ! is_array( $effective_config['skills'] ) ) {
                    $effective_config['skills'] = [];
                }
                if ( ! self::global_skill_enabled( 'product_search' ) ) {
                    $effective_config['skills']['product_search'] = 0;
                }

                $cat_result = SA_Category_Intent_Service::process_turn( $message, $active_context, $effective_config );
                if ( is_array( $cat_result ) && ! empty( $cat_result['handled'] ) ) {
                    $raw_reply      = (string) ( $cat_result['reply'] ?? '' );
                    $formatted_reply = self::format_for_whatsapp( $raw_reply );
                    $next_active    = is_array( $cat_result['active_context'] ?? null ) ? $cat_result['active_context'] : $active_context;

                    if ( $config['memory_minutes'] > 0 ) {
                        $last_ctx_msg_id = is_array( $context ) ? (string) ( $context['last_message_id'] ?? '' ) : '';
                        if ( '' === $inbox_key || $last_ctx_msg_id !== $inbox_key ) {
                            $new_history = $history;
                            $new_history[] = [ 'role' => 'user', 'content' => $message ];
                            $new_history[] = [ 'role' => 'assistant', 'content' => $formatted_reply ];

                            $max_h = absint( $config['max_history'] ?? 4 );
                            if ( count( $new_history ) > $max_h * 2 ) {
                                $new_history = array_slice( $new_history, -( $max_h * 2 ) );
                            }

                            $synced_known = is_array( $next_active['filters'] ?? null ) ? $next_active['filters'] : [];
                            if ( ! empty( $next_active['gender'] ) ) {
                                $synced_known['gender'] = $next_active['gender'];
                            }

                            $new_context = [
                                'history'            => $new_history,
                                'known_context'      => [
                                    'type'  => $next_active['product_type'] ?? null,
                                    'known' => $synced_known,
                                ],
                                'conversation_state' => array_merge( $conversation_state, [
                                    'last_product_ids' => (array) ( $next_active['last_product_ids'] ?? [] ),
                                ] ),
                                'active_context'     => $next_active,
                                'last_message_id'    => $inbox_key,
                            ];
                            set_transient( $cache_key, $new_context, $config['memory_minutes'] * MINUTE_IN_SECONDS );
                        }
                    }

                    $store_name = function_exists( 'get_bloginfo' ) ? sanitize_text_field( (string) get_bloginfo( 'name' ) ) : '';
                    $payload = [
                        'reply'           => $orchestrator_mode ? '' : $formatted_reply,
                        'store_name'      => $store_name,
                        'intent'          => $cat_result['intent'],
                        'category_id'     => $cat_result['category_id'],
                        'category_name'   => $cat_result['category_name'],
                        'category_url'    => $cat_result['category_url'],
                        'product_type'    => $cat_result['product_type'],
                        'gender'          => $cat_result['gender'],
                        'filters'         => $cat_result['filters'],
                        'context_updated' => (bool) $cat_result['context_updated'],
                        'products'        => $cat_result['products'] ?? [],
                    ];
                    if ( $orchestrator_mode ) {
                        $payload['suggested_reply']                = $formatted_reply;
                        $payload['reply_deferred_to_orchestrator'] = true;
                    }
                    return $finish_complete( $payload, 200 );
                }
            }
            // === END CATEGORY INTENT PIPELINE ===

            $analysis = Service_Conversation_Analyzer::analyze( $message, $history, $known_context, $conversation_state );

            if ( ! empty( $analysis['error'] ) ) {
                $err_reply = self::format_for_whatsapp( self::render_template( 'service_error', [], 'عذراً، أواجه ضغطاً في الخدمة حالياً. حاول مرة أخرى بعد قليل.' ) );
                return $finish_fail( 'RETRYABLE', 'analyzer_temporary_error', [ 'reply' => $err_reply ], 503, [ 'Retry-After' => 10 ] );
            }

            $action = $analysis['action'] ?? 'chitchat';
            $message_lc = function_exists( 'mb_strtolower' ) ? mb_strtolower( $message, 'UTF-8' ) : strtolower( $message );
            $asks_offers = (bool) preg_match( '/(عروض|خصومات|تخفيضات|التخفيض|الخصم|فيه خصم|عندكم خصم|عرض خاص|sale|discount|offer)/iu', $message_lc );
            if ( $asks_offers ) $action = 'offers';
            $reply = '';
            $legacy_products = [];
            $legacy_intent = $action;

            if ( 'offers' === $action ) {
                $legacy_intent = 'OFFERS';
                if ( empty( $config['skills']['offers'] ) || ! self::global_skill_enabled( 'offers_discounts' ) ) {
                    $reply = self::render_template( 'offers_disabled', [], 'العروض والخصومات غير متاحة حالياً.' );
                } elseif ( ! function_exists( 'wc_get_products' ) ) {
                    $reply = 'تعذر الوصول إلى عروض المتجر حالياً.';
                } else {
                    $sale_products = wc_get_products( [ 'status' => 'publish', 'limit' => 3, 'return' => 'objects', 'on_sale' => true, 'stock_status' => 'instock', 'orderby' => 'date', 'order' => 'DESC' ] );
                    $lines = [];
                    foreach ( (array) $sale_products as $product ) {
                        if ( ! $product || ! $product->is_visible() || ! $product->is_purchasable() || ! $product->is_in_stock() || ! $product->is_on_sale() ) continue;
                        $pid = (int) $product->get_id();
                        $p_name = (string) $product->get_name();
                        $p_price = wp_strip_all_tags( (string) $product->get_price_html() );
                        $p_price_num = (float) $product->get_price();
                        $p_currency = function_exists( 'get_woocommerce_currency' ) ? get_woocommerce_currency() : '';
                        $p_desc = trim( wp_strip_all_tags( (string) $product->get_short_description() ) );
                        $p_img_id = method_exists( $product, 'get_image_id' ) ? (int) $product->get_image_id() : 0;
                        $p_img = ( $p_img_id > 0 && function_exists( 'wp_get_attachment_image_url' ) )
                            ? esc_url_raw( (string) wp_get_attachment_image_url( $p_img_id, 'large' ) )
                            : '';
                        $url = esc_url_raw( get_permalink( $pid ) );
                        $legacy_products[] = [
                            'id'                => $pid,
                            'name'              => $p_name,
                            'price'             => $p_price,
                            'price_numeric'     => $p_price_num,
                            'currency'          => $p_currency,
                            'short_description' => $p_desc,
                            'image'             => $p_img,
                            'url'               => $url,
                        ];
                        $line = '• ' . $p_name . ' — ' . $p_price;
                        if ( 'single_link' === $config['reply_mode'] && empty( $lines ) && $url ) $line .= "\n" . $url;
                        elseif ( 'multi_link' === $config['reply_mode'] && $url ) $line .= "\n" . $url;
                        $lines[] = $line;
                    }
                    $reply = $lines
                        ? self::render_template( 'offers_intro', [], 'هذه بعض العروض المتاحة حالياً:' ) . "\n" . implode( "\n\n", $lines )
                        : self::render_template( 'no_offers', [], 'لا توجد منتجات عليها عروض ومتاحة للشراء حالياً.' );
                }
            } elseif ( 'chitchat' === $action || 'refuse' === $action ) {
                if ( empty( $config['skills']['greeting'] ) ) {
                    $reply = self::render_template( 'service_error', [], 'عذراً، لا أستطيع تنفيذ هذا الطلب.' );
                } else {
                    $reply = $analysis['reply_text'] ?: 'كيف يمكنني مساعدتك؟';
                }
                if ( 'chitchat' === $action && ! empty( $config['skills']['greeting'] ) ) {
                    $reply = self::render_template( 'welcome' ) . "\n" . $reply;
                }
            } elseif ( 'ask' === $action ) {
                $reply = $analysis['question_text'] ?: self::render_template( 'ask_clarification', [], 'هل يمكنك توضيح طلبك؟' );
            } else {
                $legacy_intent = 'PRODUCT_SEARCH';
                if ( empty( $config['skills']['product_search'] ) || ! self::global_skill_enabled( 'product_search' ) ) {
                    $reply = self::render_template( 'search_disabled', [], 'البحث غير متاح حالياً.' );
                    return $finish_complete( [ 'reply' => self::format_for_whatsapp( $reply ) ], 200 );
                }

                $type  = $analysis['type'] ?? ( $known_context['type'] ?? null );
                $known = is_array( $analysis['known'] ?? null ) ? $analysis['known'] : [];
                $msg_l = function_exists( 'mb_strtolower' ) ? mb_strtolower( $message, 'UTF-8' ) : strtolower( $message );

                if ( ( function_exists( 'mb_strpos' ) ? mb_strpos( $msg_l, 'عطر' ) : strpos( $msg_l, 'عطر' ) ) !== false ) { $type = 'عطر'; }
                elseif ( ( function_exists( 'mb_strpos' ) ? mb_strpos( $msg_l, 'حذاء' ) : strpos( $msg_l, 'حذاء' ) ) !== false ) { $type = 'حذاء'; }
                elseif ( ( function_exists( 'mb_strpos' ) ? mb_strpos( $msg_l, 'ساعة' ) : strpos( $msg_l, 'ساعة' ) ) !== false ) { $type = 'ساعة'; }

                if ( null === $type && empty( $known['name_contains'] ) ) {
                    $reply = self::render_template( 'ask_clarification', [], 'ماذا تبحث عنه؟' );
                } elseif ( ! class_exists( 'Service_Smart_Search' ) ) {
                    $reply = self::render_template( 'search_disabled', [], 'البحث غير متاح.' );
                } else {
                    $products = null === $type
                        ? Service_Smart_Search::find_by_name_contains( $known['name_contains'], 5, $known )
                        : Service_Smart_Search::find( $type, $known, $message, 5 );

                    if ( empty( $products ) ) {
                        $reply = self::render_template( 'no_products', [], 'لم أجد منتجات مطابقة.' );
                    } else {
                        $listed = array_slice( $products, 0, 3 );
                        $legacy_products = $listed;
                        $lines  = [ self::render_template( 'products_intro', [], 'وجدت لك:' ) ];
                        $first  = true;

                        foreach ( $listed as $p ) {
                            $name  = $p['name'] ?? 'منتج';
                            $price = $p['price'] ?? '';
                            $raw_url = $p['url'] ?? '';
                            $safe_url = esc_url_raw( rawurldecode( $raw_url ) );

                            $line = '• ' . $name;
                            if ( '' !== $price ) $line .= ' — ' . $price;

                            if ( 'single_link' === $config['reply_mode'] ) {
                                if ( $first && '' !== $safe_url ) {
                                    $line .= "\n\n " . $safe_url . " \n";
                                    $first = false;
                                }
                            } elseif ( 'multi_link' === $config['reply_mode'] ) {
                                if ( '' !== $safe_url ) $line .= "\n " . $safe_url;
                            }
                            $lines[] = $line;
                        }
                        $lines[] = self::render_template( 'products_outro', [], 'هل تريد تفاصيل أكثر؟' );
                        $reply = implode( "\n", $lines );
                    }
                }
            }

            if ( '' === trim( (string) $reply ) ) {
                $reply = 'كيف يمكنني مساعدتك اليوم؟';
            }

            if ( $config['memory_minutes'] > 0 ) {
                $last_ctx_msg_id = is_array( $context ) ? (string) ( $context['last_message_id'] ?? '' ) : '';
                if ( '' === $inbox_key || $last_ctx_msg_id !== $inbox_key ) {
                    $new_history = $history;
                    $new_history[] = [ 'role' => 'user', 'content' => $message ];
                    $new_history[] = [ 'role' => 'assistant', 'content' => $reply ];

                    $max_h = absint( $config['max_history'] ?? 4 );
                    if ( count( $new_history ) > $max_h * 2 ) {
                        $new_history = array_slice( $new_history, -( $max_h * 2 ) );
                    }

                    $memory_type = $analysis['type'] ?? ( $known_context['type'] ?? null );
                    $memory_known = is_array( $analysis['known'] ?? null ) ? $analysis['known'] : [];
                    if ( null !== $memory_type && [] === $memory_known && is_array( $known_context['known'] ?? null ) ) {
                        $memory_known = $known_context['known'];
                    }
                    $new_context = [
                        'history'            => $new_history,
                        'known_context'      => [ 'type' => $memory_type, 'known' => $memory_known ],
                        'conversation_state' => $analysis['conversation_state'] ?? $conversation_state,
                        'active_context'     => $active_context,
                        'last_message_id'    => $inbox_key,
                    ];
                    set_transient( $cache_key, $new_context, $config['memory_minutes'] * MINUTE_IN_SECONDS );
                }
            }

            $store_name = function_exists( 'get_bloginfo' ) ? sanitize_text_field( (string) get_bloginfo( 'name' ) ) : '';
            $final_reply = self::format_for_whatsapp( $reply );
            $final_payload = [
                'reply'      => $orchestrator_mode ? '' : $final_reply,
                'store_name' => $store_name,
                'intent'     => $legacy_intent,
                'products'   => $legacy_products,
            ];
            if ( $orchestrator_mode ) {
                $final_payload['suggested_reply']                = $final_reply;
                $final_payload['reply_deferred_to_orchestrator'] = true;
            }
            return $finish_complete( $final_payload, 200 );
        } catch ( Throwable $e ) {
            error_log( '[Smart Advisor] WhatsApp reply error: ' . $e->getMessage() );
            return $finish_fail( 'RETRYABLE', $e->getMessage(), [ 'reply' => 'حدث خطأ مؤقت أثناء معالجة الرسالة.' ], 503, [ 'Retry-After' => 10 ] );
        }
    }

    private static function is_orchestrator_mode( $request ) {
        if ( ! is_object( $request ) || ! method_exists( $request, 'get_param' ) ) {
            return false;
        }
        $orch = $request->get_param( 'orchestrator' );
        $suppress = $request->get_param( 'suppress_reply' );
        $compose_mode = strtolower( trim( (string) $request->get_param( 'compose_mode' ) ) );
        $reply_owner  = strtolower( trim( (string) $request->get_param( 'reply_owner' ) ) );
        $header_owner = method_exists( $request, 'get_header' ) ? strtolower( trim( (string) $request->get_header( 'X-SA-Reply-Owner' ) ) ) : '';

        return ! empty( $orch )
            || ( null !== $suppress && filter_var( $suppress, FILTER_VALIDATE_BOOLEAN ) )
            || 'structured_only' === $compose_mode
            || 'automate_ai' === $reply_owner
            || 'automate_ai' === $header_owner;
    }

    private static function format_for_whatsapp( $text ) {
        $text = preg_replace( '/\*\*(.+?)\*\*/u', '*$1*', (string) $text );
        $text = preg_replace( "/\n{3,}/u", "\n\n", $text );
        return trim( $text );
    }
}
SA_WhatsApp_Reply::init();

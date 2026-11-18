<?php
/**
 * Smart Advisor - AI Provider (with Fallback Chain)
 * Version: 2.1.0
 *
 * Fixes over v1.0.0:
 *  - 404 no longer stops the chain (was classified as bad_request → stopped)
 *  - Circuit breaker: provider skipped for 60s after 2 consecutive failures
 *  - Throttled logging: 1 line per minute per provider (was: every request)
 *  - keep_alive sent to both /api/chat (native) and /v1/chat/completions
 *  - Empty result from one provider no longer silently ignored (bumps circuit)
 *
 * Fallback flow: Gateway → Ollama → Gemini Direct → Apology
 *
 * @package SmartAdvisor
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class Core_Gemini {

    const OLLAMA_URL  = 'http://172.18.0.8:11434/v1/chat/completions';
    const GEMINI_BASE = 'https://generativelanguage.googleapis.com/v1beta/models/';

    /** Circuit breaker: skip a provider for 60s after this many failures. */
    const CB_THRESHOLD = 2;
    const CB_WINDOW    = 60;

    public static function chat( $messages, $system_prompt, $enable_web_search = false ) {
        $provider = Core_Settings::get( 'provider', 'gateway' );
        $chain    = self::build_chain( $provider );

        foreach ( $chain as $step ) {
            // Circuit breaker — skip if recently failed
            if ( self::is_circuit_open( $step ) ) {
                self::throttled_log( $step . ' circuit open — skipping' );
                continue;
            }

            $result = null;
            switch ( $step ) {
                case 'gateway': $result = self::chat_gateway( $messages, $system_prompt ); break;
                case 'ollama':  $result = self::chat_ollama( $messages, $system_prompt ); break;
                case 'gemini':  $result = self::chat_direct_gemini( $messages, $system_prompt ); break;
            }

            // Success → reset circuit and return
            if ( is_string( $result ) && trim( $result ) !== '' ) {
                self::reset_circuit( $step );
                return $result;
            }

            if ( is_wp_error( $result ) ) {
                $code = $result->get_error_code();

                // Stop ONLY on auth failures or malformed requests (won't change
                // by trying another provider).
                if ( in_array( $code, [ 'auth_error', 'bad_request' ], true ) ) {
                    self::throttled_log( "{$step} failed ({$code}) — stopping chain" );
                    return $result;
                }

                // Everything else transitions to the next provider:
                // network_error, rate_limit, not_found, invalid_response, unknown
                self::throttled_log( "{$step} failed ({$code}) — trying next" );
                self::bump_circuit( $step );
            } else {
                self::throttled_log( "{$step} returned empty — trying next" );
                self::bump_circuit( $step );
            }
        }

        return new WP_Error(
            'all_providers_failed',
            'عذراً، خدمة الذكاء الاصطناعي غير متاحة مؤقتاً. حاول بعد قليل.'
        );
    }

    private static function build_chain( $primary ) {
        switch ( $primary ) {
            case 'ollama': return [ 'ollama', 'gemini' ];
            case 'gemini': return [ 'gemini' ];
            case 'gateway':
            default:       return [ 'gateway', 'ollama', 'gemini' ];
        }
    }

    /**
     * Classify HTTP error codes.
     * 404 is now `not_found` (fallback-able), not `bad_request` (stop).
     */
    private static function classify_http_error( $code ) {
        if ( $code === 401 || $code === 403 ) return 'auth_error';
        if ( $code === 404 )                return 'not_found';
        if ( $code === 429 )                return 'rate_limit';
        if ( $code >= 500 )                 return 'network_error';
        if ( $code >= 400 )                 return 'bad_request';
        return 'unknown';
    }

    // -------------------------------------------------------------------------
    // Circuit breaker
    // -------------------------------------------------------------------------

    private static function cb_key( $step ) {
        return 'sa_cb_' . sanitize_key( $step );
    }

    private static function is_circuit_open( $step ) {
        return get_transient( self::cb_key( $step ) ) !== false;
    }

    private static function bump_circuit( $step ) {
        $count_key = self::cb_key( $step ) . '_count';
        $count     = (int) get_transient( $count_key );
        $count++;
        set_transient( $count_key, $count, self::CB_WINDOW );

        if ( $count >= self::CB_THRESHOLD ) {
            set_transient( self::cb_key( $step ), 1, self::CB_WINDOW );
        }
    }

    private static function reset_circuit( $step ) {
        delete_transient( self::cb_key( $step ) );
        delete_transient( self::cb_key( $step ) . '_count' );
    }

    // -------------------------------------------------------------------------
    // Throttled logging
    // -------------------------------------------------------------------------

    private static function throttled_log( $msg ) {
        $key = 'sa_log_' . md5( $msg );
        if ( get_transient( $key ) !== false ) return;
        set_transient( $key, 1, 60 );
        error_log( '[SA] ' . $msg );
    }

    // -------------------------------------------------------------------------
    // Gateway (OpenAI-compat)
    // -------------------------------------------------------------------------

    private static function chat_gateway( $messages, $system_prompt ) {
        $url = Core_Settings::get( 'gateway_url', '' );
        $key = Core_Settings::get( 'gateway_key', '' );

        if ( empty( $url ) ) {
            return new WP_Error( 'not_configured', 'Gateway URL غير معد' );
        }

        $headers = [ 'Content-Type' => 'application/json' ];
        if ( ! empty( $key ) ) {
            $headers['Authorization'] = 'Bearer ' . $key;
        }

        $contents = [];
        if ( ! empty( $system_prompt ) ) {
            $contents[] = [ 'role' => 'system', 'content' => $system_prompt ];
        }
        foreach ( $messages as $msg ) {
            $role = ( ( $msg['role'] ?? 'user' ) === 'assistant' ) ? 'assistant' : 'user';
            $contents[] = [ 'role' => $role, 'content' => (string) ( $msg['content'] ?? '' ) ];
        }

        $response = wp_remote_post( $url, [
            'timeout' => apply_filters( 'sa_ai_http_timeout', 20 ),
            'headers' => $headers,
            'body'    => wp_json_encode( [
                'model'       => Core_Settings::get( 'model', 'smart-advisor' ),
                'messages'    => $contents,
                'temperature' => 0.7,
            ] ),
        ] );

        if ( is_wp_error( $response ) ) {
            return new WP_Error( 'network_error', 'تعذر الاتصال بالبوابة' );
        }

        $code = (int) wp_remote_retrieve_response_code( $response );
        $data = json_decode( wp_remote_retrieve_body( $response ), true );

        if ( $code < 200 || $code >= 300 ) {
            $type = self::classify_http_error( $code );
            $msg  = $data['error']['message'] ?? "HTTP {$code}";
            return new WP_Error( $type, sanitize_text_field( $msg ) );
        }

        $reply = $data['choices'][0]['message']['content'] ?? '';
        if ( ! is_string( $reply ) || trim( $reply ) === '' ) {
            return new WP_Error( 'invalid_response', 'رد فارغ من البوابة' );
        }

        return $reply;
    }

    // -------------------------------------------------------------------------
    // Ollama (supports both native /api/chat and OpenAI-compat /v1/chat/completions)
    // -------------------------------------------------------------------------

    private static function chat_ollama( $messages, $system_prompt ) {
        $ollama_url   = Core_Settings::get( 'ollama_url', self::OLLAMA_URL );
        $ollama_model = Core_Settings::get( 'ollama_model', 'qwen2.5:1.5b' );

        $contents = [];
        if ( ! empty( $system_prompt ) ) {
            $contents[] = [ 'role' => 'system', 'content' => $system_prompt ];
        }
        foreach ( array_slice( $messages, -10 ) as $msg ) {
            $role = ( ( $msg['role'] ?? 'user' ) === 'assistant' ) ? 'assistant' : 'user';
            $contents[] = [ 'role' => $role, 'content' => (string) ( $msg['content'] ?? '' ) ];
        }

        $is_native_api = ( strpos( $ollama_url, '/api/chat' ) !== false );

        if ( $is_native_api ) {
            // Native Ollama API
            $body = [
                'model'      => $ollama_model,
                'messages'   => $contents,
                'stream'     => false,
                'keep_alive' => '24h',
                'options'    => [
                    'temperature' => 0.7,
                    'num_predict' => 200,
                    'num_ctx'     => 16384,
                ],
            ];
        } else {
            // OpenAI-compatible
            $body = [
                'model'       => $ollama_model,
                'messages'    => $contents,
                'temperature' => 0.7,
                'max_tokens'  => 200,
                'keep_alive'  => '24h',
            ];
        }

        $response = wp_remote_post( $ollama_url, [
            'timeout' => apply_filters( 'sa_ai_http_timeout', 20 ),
            'headers' => [ 'Content-Type' => 'application/json' ],
            'body'    => wp_json_encode( $body ),
        ] );

        if ( is_wp_error( $response ) ) {
            return new WP_Error( 'network_error', 'تعذر الاتصال بـ Ollama' );
        }

        $code = (int) wp_remote_retrieve_response_code( $response );
        $data = json_decode( wp_remote_retrieve_body( $response ), true );

        if ( $code < 200 || $code >= 300 ) {
            $type = self::classify_http_error( $code );
            return new WP_Error( $type, "Ollama HTTP {$code}" );
        }

        $reply = $is_native_api
            ? ( $data['message']['content'] ?? '' )
            : ( $data['choices'][0]['message']['content'] ?? '' );

        if ( ! is_string( $reply ) || trim( $reply ) === '' ) {
            return new WP_Error( 'invalid_response', 'رد فارغ من Ollama' );
        }

        return $reply;
    }

    // -------------------------------------------------------------------------
    // Gemini Direct
    // -------------------------------------------------------------------------

    private static function chat_direct_gemini( $messages, $system_prompt ) {
        $api_key = Core_Settings::get( 'api_key', '' );
        if ( empty( $api_key ) ) {
            return new WP_Error( 'not_configured', 'مفتاح Gemini غير مدخل' );
        }

        $contents = [];
        foreach ( $messages as $msg ) {
            $contents[] = [
                'role'  => ( ( $msg['role'] ?? 'user' ) === 'assistant' ) ? 'model' : 'user',
                'parts' => [ [ 'text' => (string) ( $msg['content'] ?? '' ) ] ],
            ];
        }

        $body = [ 'contents' => $contents ];
        if ( ! empty( $system_prompt ) ) {
            $body['systemInstruction'] = [ 'parts' => [ [ 'text' => $system_prompt ] ] ];
        }

        $model = Core_Settings::get( 'model', 'gemini-2.5-flash' );
        if ( $model === '' || $model === 'smart-advisor' ) {
            $model = 'gemini-2.5-flash';
        }
        $model = preg_replace( '/[^a-zA-Z0-9._-]/', '', $model );
        if ( $model === '' ) {
            $model = 'gemini-2.5-flash';
        }

        $response = wp_remote_post(
            self::GEMINI_BASE . rawurlencode( $model ) . ':generateContent',
            [
                'timeout' => apply_filters( 'sa_ai_http_timeout', 20 ),
                'headers' => [
                    'Content-Type'   => 'application/json',
                    'x-goog-api-key' => $api_key,
                ],
                'body' => wp_json_encode( $body ),
            ]
        );

        if ( is_wp_error( $response ) ) {
            return new WP_Error( 'network_error', 'تعذر الاتصال بجوجل' );
        }

        $code = (int) wp_remote_retrieve_response_code( $response );
        $data = json_decode( wp_remote_retrieve_body( $response ), true );

        if ( $code < 200 || $code >= 300 ) {
            $type = self::classify_http_error( $code );
            $msg  = $data['error']['message'] ?? "HTTP {$code}";
            return new WP_Error( $type, sanitize_text_field( $msg ) );
        }

        $reply = $data['candidates'][0]['content']['parts'][0]['text'] ?? '';
        if ( ! is_string( $reply ) || trim( $reply ) === '' ) {
            return new WP_Error( 'invalid_response', 'رد فارغ من Gemini' );
        }

        return $reply;
    }
}
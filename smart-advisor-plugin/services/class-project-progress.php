<?php
/**
 * Project progress tracking for Smart Advisor.
 * Stores per-need completion and actual spend, scoped to the owning customer.
 */
if ( ! defined( 'ABSPATH' ) ) exit;

class SA_Project_Progress {
    public static function init() {
        add_action( 'rest_api_init', [ __CLASS__, 'register_routes' ] );
    }

    public static function register_routes() {
        register_rest_route( 'smart-advisor/v1', '/profile/projects/(?P<id>\d+)/progress', [
            [
                'methods' => 'GET',
                'callback' => [ __CLASS__, 'get_progress' ],
                'permission_callback' => [ 'SA_Customer_Profile', 'authenticate_customer' ],
            ],
            [
                'methods' => 'POST',
                'callback' => [ __CLASS__, 'save_progress' ],
                'permission_callback' => [ 'SA_Customer_Profile', 'authenticate_customer' ],
            ],
        ] );
    }

    private static function table() {
        global $wpdb;
        return $wpdb->prefix . 'sa_project_progress';
    }

    private static function owned_project( $project_id, $user_id ) {
        global $wpdb;
        return $wpdb->get_row( $wpdb->prepare(
            'SELECT id, budget_min, budget_max FROM ' . $wpdb->prefix . 'sa_customer_projects WHERE id=%d AND user_id=%d AND status=%s',
            absint( $project_id ), absint( $user_id ), 'active'
        ), ARRAY_A );
    }

    private static function consent_error() {
        if ( ! class_exists( 'SA_Customer_Behavior' ) || ! SA_Customer_Behavior::has_consent( get_current_user_id() ) ) {
            return new WP_Error( 'sa_consent_required', 'الموافقة على حفظ بيانات المستشار مطلوبة لإدارة تقدم المشروع.', [ 'status' => 403 ] );
        }
        return true;
    }

    public static function get_progress( $request ) {
        $allowed = self::consent_error();
        if ( is_wp_error( $allowed ) ) return $allowed;

        global $wpdb;
        $user_id = get_current_user_id();
        $project_id = absint( $request['id'] );
        $project = self::owned_project( $project_id, $user_id );
        if ( ! $project ) return new WP_Error( 'sa_project_not_found', 'المشروع غير موجود أو لا تملك صلاحية الوصول إليه.', [ 'status' => 404 ] );

        $items = $wpdb->get_results( $wpdb->prepare(
            'SELECT item_key, item_title, status, actual_cost, notes, updated_at FROM ' . self::table() . ' WHERE project_id=%d AND user_id=%d ORDER BY id ASC',
            $project_id, $user_id
        ), ARRAY_A );

        return rest_ensure_response( self::summarize( $items, $project ) );
    }

    public static function save_progress( $request ) {
        $allowed = self::consent_error();
        if ( is_wp_error( $allowed ) ) return $allowed;
        $limited = SA_Customer_Profile::check_user_rate_limit( get_current_user_id(), 'save_progress' );
        if ( is_wp_error( $limited ) ) return $limited;

        global $wpdb;
        $user_id = get_current_user_id();
        $project_id = absint( $request['id'] );
        $project = self::owned_project( $project_id, $user_id );
        if ( ! $project ) return new WP_Error( 'sa_project_not_found', 'المشروع غير موجود أو لا تملك صلاحية الوصول إليه.', [ 'status' => 404 ] );

        $raw_items = $request->get_param( 'items' );
        if ( ! is_array( $raw_items ) || count( $raw_items ) > 40 ) {
            return new WP_Error( 'sa_invalid_progress', 'بيانات التقدم غير صالحة.', [ 'status' => 400 ] );
        }

        $now = current_time( 'mysql', true );
        $wpdb->query( 'START TRANSACTION' );

        // Optional plan synchronization: remove obsolete checklist keys only for this owned project.
        // Existing matching rows (status, cost and notes) are preserved by the upsert below.
        $sync_keys = $request->get_param( 'sync_keys' );
        if ( is_array( $sync_keys ) ) {
            $sync_keys = array_values( array_unique( array_filter( array_map( 'sanitize_key', $sync_keys ) ) ) );
            if ( count( $sync_keys ) > 40 ) {
                $wpdb->query( 'ROLLBACK' );
                return new WP_Error( 'sa_invalid_sync', 'قائمة مزامنة احتياجات المشروع غير صالحة.', [ 'status' => 400 ] );
            }
            if ( $sync_keys ) {
                $placeholders = implode( ',', array_fill( 0, count( $sync_keys ), '%s' ) );
                $sql = $wpdb->prepare(
                    'DELETE FROM ' . self::table() . " WHERE project_id=%d AND user_id=%d AND item_key NOT IN ({$placeholders})",
                    array_merge( [ $project_id, $user_id ], $sync_keys )
                );
            } else {
                $wpdb->query( 'ROLLBACK' );
                return new WP_Error( 'sa_empty_sync', 'empty sync_keys refused; use explicit delete', [ 'status' => 400 ] );
            }
            if ( false === $wpdb->query( $sql ) ) {
                $wpdb->query( 'ROLLBACK' );
                return new WP_Error( 'sa_progress_sync_failed', 'تعذرت مزامنة احتياجات المشروع.', [ 'status' => 500 ] );
            }
        }

        foreach ( $raw_items as $raw ) {
            if ( ! is_array( $raw ) ) continue;
            $key = sanitize_key( $raw['item_key'] ?? '' );
            $title = sanitize_text_field( $raw['item_title'] ?? '' );
            $status = sanitize_key( $raw['status'] ?? 'pending' );
            $cost = isset( $raw['actual_cost'] ) ? (float) $raw['actual_cost'] : 0;
            $notes_provided = array_key_exists( 'notes', $raw );
            $notes = $notes_provided ? sanitize_textarea_field( (string) $raw['notes'] ) : null;

            if ( '' === $key || strlen( $key ) > 100 || '' === $title || strlen( $title ) > 190 ) continue;
            if ( ! in_array( $status, [ 'pending', 'in_progress', 'completed' ], true ) ) {
                $wpdb->query( 'ROLLBACK' );
                return new WP_Error( 'sa_invalid_status', 'حالة إنجاز غير صالحة.', [ 'status' => 400 ] );
            }
            if ( $cost < 0 || $cost > 999999999999 ) {
                $wpdb->query( 'ROLLBACK' );
                return new WP_Error( 'sa_invalid_cost', 'قيمة المصروف غير صالحة.', [ 'status' => 400 ] );
            }

            if ( null === $notes ) {
                $sql = $wpdb->prepare(
                    'INSERT INTO ' . self::table() . ' (project_id,user_id,item_key,item_title,status,actual_cost,notes,created_at,updated_at)
                     VALUES (%d,%d,%s,%s,%s,%f,NULL,%s,%s)
                     ON DUPLICATE KEY UPDATE item_title=VALUES(item_title), status=VALUES(status), actual_cost=VALUES(actual_cost), notes=COALESCE(VALUES(notes), notes), updated_at=VALUES(updated_at)',
                    $project_id, $user_id, $key, $title, $status, $cost, $now, $now
                );
            } else {
                $sql = $wpdb->prepare(
                    'INSERT INTO ' . self::table() . ' (project_id,user_id,item_key,item_title,status,actual_cost,notes,created_at,updated_at)
                     VALUES (%d,%d,%s,%s,%s,%f,%s,%s,%s)
                     ON DUPLICATE KEY UPDATE item_title=VALUES(item_title), status=VALUES(status), actual_cost=VALUES(actual_cost), notes=COALESCE(VALUES(notes), notes), updated_at=VALUES(updated_at)',
                    $project_id, $user_id, $key, $title, $status, $cost, $notes, $now, $now
                );
            }
            if ( false === $wpdb->query( $sql ) ) {
                $wpdb->query( 'ROLLBACK' );
                return new WP_Error( 'sa_progress_save_failed', 'تعذر حفظ تقدم المشروع. حاول مرة أخرى.', [ 'status' => 500 ] );
            }
        }
        $wpdb->query( 'COMMIT' );

        $items = $wpdb->get_results( $wpdb->prepare(
            'SELECT item_key, item_title, status, actual_cost, notes, updated_at FROM ' . self::table() . ' WHERE project_id=%d AND user_id=%d ORDER BY id ASC',
            $project_id, $user_id
        ), ARRAY_A );

        $wpdb->update( $wpdb->prefix . 'sa_customer_projects', [ 'updated_at' => $now ], [ 'id' => $project_id, 'user_id' => $user_id ], [ '%s' ], [ '%d', '%d' ] );
        return rest_ensure_response( self::summarize( $items, $project ) );
    }

    private static function summarize( $items, $project ) {
        $total = count( $items );
        $completed = 0;
        $in_progress = 0;
        $spent = 0.0;
        foreach ( $items as $item ) {
            if ( 'completed' === $item['status'] ) $completed++;
            if ( 'in_progress' === $item['status'] ) $in_progress++;
            $spent += (float) $item['actual_cost'];
        }
        $budget = null !== $project['budget_max'] ? (float) $project['budget_max'] : null;
        return [
            'items' => array_map( function( $item ) {
                return [
                    'item_key' => (string) $item['item_key'],
                    'item_title' => (string) $item['item_title'],
                    'status' => (string) $item['status'],
                    'actual_cost' => (float) $item['actual_cost'],
                    'notes' => (string) $item['notes'],
                    'updated_at' => (string) $item['updated_at'],
                ];
            }, $items ),
            'summary' => [
                'total_items' => $total,
                'completed_items' => $completed,
                'in_progress_items' => $in_progress,
                'progress_percent' => $total ? (int) round( ( $completed / $total ) * 100 ) : 0,
                'spent' => round( $spent, 2 ),
                'budget' => $budget,
                'remaining' => null !== $budget ? round( $budget - $spent, 2 ) : null,
            ],
        ];
    }
}

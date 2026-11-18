<?php
if ( ! defined( 'ABSPATH' ) ) exit;

interface MBA_Module_Interface {
    public static function search_products( $query, $limit = 5 );
    public static function get_config();
}

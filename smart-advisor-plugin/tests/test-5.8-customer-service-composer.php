<?php
$root = dirname( __DIR__ );
$composer = file_get_contents( $root . '/services/class-customer-service-composer.php' );
$settings = file_get_contents( $root . '/core/class-customer-service-settings.php' );
$controller = file_get_contents( $root . '/core/class-chat-controller.php' );
$frontend = file_get_contents( $root . '/core/class-frontend.php' );
$js = file_get_contents( $root . '/assets/js/chat.js' );
$main = file_get_contents( $root . '/smart-advisor.php' );
$checks = [
    'plugin version is 6.4.0' => strpos( $main, "Version: 6.5.0" ) !== false && strpos( $main, "'6.5.0'" ) !== false,
    'composer loaded before chat controller' => strpos( $main, "services/class-customer-service-composer.php" ) < strpos( $main, "core/class-chat-controller.php" ),
    'service turns are detected before analyzer' => strpos( $controller, 'SA_Customer_Service_Composer::is_service_turn' ) < strpos( $controller, 'Service_Conversation_Analyzer::analyze' ),
    'composer supports forced admin test path' => strpos( $composer, 'force_service' ) !== false,
    'skills and permissions are deep merged' => strpos( $settings, 'skills', strpos( $settings, 'get_config' ) ) !== false && strpos( $settings, 'permissions' ) !== false,
    'admin test delegates to composer' => strpos( $settings, 'SA_Customer_Service_Composer::compose' ) !== false,
    'provider errors are surfaced to admin test' => strpos( $settings, 'provider_error' ) !== false,
    'manual policies take precedence over page content' => strpos( $composer, 'continue;' ) !== false,
    'missing policy facts cannot be invented' => strpos( $composer, 'لا تستنتج ولا تخترع' ) !== false,
    'service composer returns path metadata' => strpos( $composer, 'customer_service' ) !== false,
    'chat response applies styling centrally' => strpos( $controller, 'SA_Customer_Service_Composer::style( $reply )' ) !== false,
    'assistant name and welcome are localized' => strpos( $frontend, 'assistantName' ) !== false && strpos( $frontend, 'welcomeMessage' ) !== false,
    'welcome bubble is shown once on fresh chat' => strpos( $js, "if (!uiMessages.length && cfg.welcomeMessage)" ) !== false,
    'product cards receive brand and stock data' => strpos( $controller, "'brand' => sanitize_text_field" ) !== false && strpos( $controller, "'in_stock' => isset" ) !== false,
];
$failed = 0;
foreach ( $checks as $name => $ok ) { echo ( $ok ? 'PASS ' : 'FAIL ' ) . $name . "\n"; if ( ! $ok ) $failed++; }
echo $failed ? "$failed failed assertion(s)\n" : "All 5.8.0 checks passed\n";
exit( $failed ? 1 : 0 );

<?php
/** WhatsApp memory regression tests for 6.3.3. */
define( 'ABSPATH', '/tmp/' );
define( 'MBA_SANDBOX_PATH', dirname( __DIR__ ) . '/' );
require MBA_SANDBOX_PATH . 'services/class-conversation-analyzer.php';

$failures = 0;
function wa_memory_assert( $condition, $message ) {
    global $failures;
    echo ( $condition ? "PASS " : "FAIL " ) . $message . "\n";
    if ( ! $condition ) $failures++;
}

$history = [
    [ 'role' => 'user', 'content' => 'أريد عطر' ],
    [ 'role' => 'assistant', 'content' => 'هل العطر للرجال أم للنساء؟' ],
];
$context = [ 'type' => 'عطر', 'known' => [] ];
$result = Service_Conversation_Analyzer::analyze( 'رجالي', $history, $context, [] );
wa_memory_assert( ( $result['type'] ?? null ) === 'عطر', 'bare gender refinement retains perfume type' );
wa_memory_assert( ( $result['action'] ?? null ) === 'search', 'gender refinement proceeds to search' );
wa_memory_assert( ( $result['known']['gender'] ?? null ) === 'رجالي', 'gender is stored as a structured filter' );
wa_memory_assert( Service_Conversation_Analyzer::recover_type_from_history( $history ) === 'عطر', 'legacy sessions recover type from message history' );

$whatsapp = file_get_contents( MBA_SANDBOX_PATH . 'services/class-whatsapp-reply.php' );
wa_memory_assert( strpos( $whatsapp, "'known_context' => [ 'type' => \$memory_type, 'known' => \$memory_known ]" ) !== false, 'WhatsApp persists type and filters together' );
wa_memory_assert( strpos( $whatsapp, 'recover_type_from_history( $history )' ) !== false, 'WhatsApp migrates older flat memory records' );

if ( $failures ) exit( 1 );
echo "All 6.3.3 WhatsApp memory tests passed\n";

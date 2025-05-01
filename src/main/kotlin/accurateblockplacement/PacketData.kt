package accurateblockplacement

import com.comphenix.protocol.wrappers.BlockPosition

@JvmRecord
data class PacketData(val block: BlockPosition?, val protocolValue: Int)

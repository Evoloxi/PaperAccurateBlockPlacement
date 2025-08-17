package accurateblockplacement

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.events.PacketEvent
import com.comphenix.protocol.utility.MinecraftReflection
import com.comphenix.protocol.utility.StreamSerializer
import com.comphenix.protocol.wrappers.BlockPosition
import com.comphenix.protocol.wrappers.MinecraftKey
import com.comphenix.protocol.wrappers.MovingObjectPositionBlock
import com.comphenix.protocol.wrappers.nbt.NbtBase
import com.comphenix.protocol.wrappers.nbt.NbtCompound
import com.comphenix.protocol.wrappers.nbt.NbtFactory
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.Unpooled
import org.bukkit.Axis
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Orientable
import org.bukkit.block.data.type.*
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException

@Suppress("unused")
class AccurateBlockPlacement : JavaPlugin(), Listener {
    private val protocolManager by lazy { ProtocolLibrary.getProtocolManager() }

    private val playerPacketDataHashMap: MutableMap<Player?, PacketData?> =
        HashMap<Player?, PacketData?>()

    override fun onEnable() {
        logger.info("AccurateBlockPlacement loaded!")
        protocolManager.addPacketListener(object :
            PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ITEM) {
            override fun onPacketReceiving(event: PacketEvent) {
                onBlockBuildPacket(event)
            }
        })
        protocolManager.addPacketListener(object :
            PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.CUSTOM_PAYLOAD) {
            override fun onPacketReceiving(event: PacketEvent) {
                onCustomPayload(event)
            }
        })
        server.pluginManager.registerEvents(this, this)
    }


    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val packet = PacketContainer(PacketType.Play.Server.CUSTOM_PAYLOAD)
        packet.minecraftKeys.writeSafely(0, MinecraftKey("carpet", "hello"))
        val rawData = ByteArrayOutputStream()
        val outputStream = DataOutputStream(rawData)
        try {
            StreamSerializer.getDefault().serializeVarInt(outputStream, 69)
            StreamSerializer.getDefault().serializeString(outputStream, "SPIGOT-ABP")
            packet.modifier.writeSafely(
                1,
                MinecraftReflection.getPacketDataSerializer(Unpooled.wrappedBuffer(rawData.toByteArray()))
            )
            protocolManager.sendServerPacket(event.player, packet)
        } catch (_: IOException) {
        } catch (_: InvocationTargetException) {
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        playerPacketDataHashMap.remove(event.player)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBuildEvent(event: BlockPlaceEvent) {
        val player: Player = event.player
        
        val packetData = playerPacketDataHashMap[player] ?: return
        val packetBlock = packetData.block ?: return
        
        val block: Block = event.block
        if (packetBlock.x != block.x || packetBlock.y != block.y || packetBlock.z != block.z) {
            playerPacketDataHashMap.remove(player)
            return
        }
        accurateBlockProtocol(event, packetData.protocolValue)
        playerPacketDataHashMap.remove(player)
    }

    private fun accurateBlockProtocol(event: BlockPlaceEvent, protocolValue: Int) {
        var protocolValue = protocolValue
        val player: Player = event.player
        val block: Block = event.block
        val clickedBlock: Block = event.blockAgainst
        val blockData: BlockData = block.blockData
        val clickBlockData: BlockData = clickedBlock.blockData

        if (blockData is Bed) return

        if (blockData is Directional) {
            val facingIndex = protocolValue and 0xF
            if (facingIndex == 6) {
                blockData.facing = blockData.facing.oppositeFace
            } else if (facingIndex <= 5) {
                var face: BlockFace? = null
                val validFaces: MutableSet<BlockFace?> = blockData.faces
                face = when (facingIndex) {
                    0 -> BlockFace.DOWN
                    1 -> BlockFace.UP
                    2 -> BlockFace.NORTH
                    3 -> BlockFace.SOUTH
                    4 -> BlockFace.WEST
                    5 -> BlockFace.EAST
                    else -> face
                }
                if (validFaces.contains(face) && face != null) {
                    blockData.facing = face
                }
            }
            if (blockData is Chest) {
                //Merge chests if needed.
                //Make sure we don't rotate a "half-double" chest!
                blockData.type = Chest.Type.SINGLE
                val left: BlockFace = rotateCW(blockData.facing)
                // Handle clicking on a chest in the world.
                if (clickedBlock != block && clickBlockData.material === blockData.material) {
                    val clickChest: Chest = clickBlockData as Chest
                    if (clickChest.type === Chest.Type.SINGLE && blockData.facing === clickChest.facing) {
                        val relation: BlockFace? = block.getFace(clickedBlock)
                        if (left === relation) {
                            blockData.type = Chest.Type.LEFT
                        } else if (left.oppositeFace === relation) {
                            blockData.type = Chest.Type.RIGHT
                        }
                    }
                    // Handle placing a chest normally.
                } else if (!player.isSneaking) {
                    val leftBlock: BlockData = block.getRelative(left).blockData
                    val rightBlock: BlockData = block.getRelative(left.oppositeFace).blockData
                    if (leftBlock.material === blockData.material && (leftBlock as Chest).type === Chest.Type.SINGLE && leftBlock.facing === blockData.facing) {
                        blockData.type = Chest.Type.LEFT
                    } else if (rightBlock.material === blockData.material && (rightBlock as Chest).type === Chest.Type.SINGLE && rightBlock.facing === blockData.facing) {
                        blockData.type = Chest.Type.RIGHT
                    }
                }
            } else if (blockData is Stairs) {
                blockData.shape = handleStairs(block, blockData)
            }
        } else if (blockData is Orientable) {
            val validAxes: MutableSet<Axis?> = blockData.axes
            val axis: Axis? = when (protocolValue % 3) {
                0 -> Axis.X
                1 -> Axis.Y
                2 -> Axis.Z
                else -> null
            }
            if (validAxes.contains(axis) && axis != null) {
                blockData.axis = axis
            }
        }
        protocolValue = protocolValue and -0x10
        if (protocolValue >= 16) {
            if (blockData is Repeater) {
                val delay = protocolValue / 16
                if (delay >= blockData.minimumDelay && delay <= blockData.maximumDelay) {
                    blockData.delay = delay
                }
            } else if (protocolValue == 16) {
                if (blockData is Comparator) {
                    blockData.mode = Comparator.Mode.SUBTRACT
                } else if (blockData is Bisected) {
                    blockData.half = Bisected.Half.TOP
                }
            }
        }
        if (block.canPlace(blockData)) {
            block.blockData = blockData
        } else {
            event.isCancelled = true
        }
    }

    private fun rotateCW(`in`: BlockFace): BlockFace {
        return when (`in`) {
            BlockFace.NORTH -> BlockFace.EAST
            BlockFace.WEST -> BlockFace.NORTH
            BlockFace.SOUTH -> BlockFace.WEST
            BlockFace.EAST -> BlockFace.SOUTH
            else -> BlockFace.NORTH
        }
    }

    private fun handleStairs(block: Block, stairs: Stairs): Stairs.Shape {
        val half = stairs.half
        val backFace = stairs.facing
        val frontFace = backFace.oppositeFace
        val rightFace = rotateCW(backFace)
        val leftFace = rightFace.oppositeFace

        fun Block.getStairs(face: BlockFace): Stairs? =
            getRelative(face).blockData as? Stairs

        val backStairs = block.getStairs(backFace)
        val frontStairs = block.getStairs(frontFace)
        val leftStairs = block.getStairs(leftFace)
        val rightStairs = block.getStairs(rightFace)

        return when {
            backStairs?.let { it.half == half && it.facing == leftFace } == true &&
                    rightStairs?.let { it.half == half && it.facing == backFace } != true -> Stairs.Shape.OUTER_LEFT

            backStairs?.let { it.half == half && it.facing == rightFace } == true &&
                    leftStairs?.let { it.half == half && it.facing == backFace } != true -> Stairs.Shape.OUTER_RIGHT

            frontStairs?.let { it.half == half && it.facing == leftFace } == true &&
                    leftStairs?.let { it.half == half && it.facing == backFace } != true -> Stairs.Shape.INNER_LEFT

            frontStairs?.let { it.half == half && it.facing == rightFace } == true &&
                    rightStairs?.let { it.half == half && it.facing == backFace } != true -> Stairs.Shape.INNER_RIGHT

            else -> Stairs.Shape.STRAIGHT
        }
    }

    private fun onBlockBuildPacket(event: PacketEvent) {
        val player: Player? = event.player
        val packet: PacketContainer = event.packet
        val clickInformation: MovingObjectPositionBlock = packet.movingBlockPositions.read(0)
        val blockPosition: BlockPosition = clickInformation.blockPosition
        val posVector: Vector = clickInformation.posVector

        var relativeX: Double = posVector.x - blockPosition.x

        if (relativeX < 2) {
            playerPacketDataHashMap.remove(player)
            return
        }

        val protocolValue = (relativeX.toInt() - 2) / 2
        playerPacketDataHashMap.put(player, PacketData(clickInformation.blockPosition, protocolValue))
        val relativeInt = relativeX.toInt()
        relativeX -= ((relativeInt / 2) * 2).toDouble()
        posVector.setX(relativeX + blockPosition.x)
        clickInformation.posVector = posVector
        packet.movingBlockPositions.write(0, clickInformation)
    }

    private fun onCustomPayload(event: PacketEvent) {
        val packet: PacketContainer = event.packet
        val key: MinecraftKey? = packet.minecraftKeys.readSafely(0)

        if (key == null || !(key.prefix == "carpet" && key.key == "hello")) {
            return
        }

        val data: ByteBuf = packet.modifier.read(1) as ByteBuf

        try {
            val stream = DataInputStream(ByteBufInputStream(data))

            if (StreamSerializer.getDefault().deserializeVarInt(stream) != 420) return

            val rulePacket = PacketContainer(PacketType.Play.Server.CUSTOM_PAYLOAD)
            packet.minecraftKeys.write(0, MinecraftKey("carpet", "hello"))
            val abpRule: NbtCompound? = NbtFactory.ofCompound(
                "Rules",
                listOf<NbtBase<String?>?>(
                    NbtFactory.of("Value", "true"),
                    NbtFactory.of("Manager", "carpet"),
                    NbtFactory.of("Rule", "accurateBlockPlacement")
                )
            )
            val rawData = ByteArrayOutputStream()
            val outputStream = DataOutputStream(rawData)
            StreamSerializer.getDefault().serializeVarInt(outputStream, 1)
            StreamSerializer.getDefault().serializeCompound(outputStream, abpRule)
            rulePacket.modifier
                .write(1, MinecraftReflection.getPacketDataSerializer(Unpooled.wrappedBuffer(rawData.toByteArray())))
            protocolManager.sendServerPacket(event.player, rulePacket)
        } catch (_: IOException) {
        } catch (_: InvocationTargetException) {
        }
    }
}

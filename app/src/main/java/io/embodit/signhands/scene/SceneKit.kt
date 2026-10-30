package io.embodit.signhands.scene

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.signhands.domain.Q4
import io.embodit.signhands.domain.V3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. Every entity hangs below the scene root whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun V3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): V3 = V3(x, y, z)

internal fun Q4.sdk(): Quat = Quat(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: V3? = null, rotation: Q4? = null, scale: V3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setUniformScale(k: Float) {
    transformOrNull?.setScaleVector(Vector3(k, k, k))
}

internal fun Entity.setVisible(visible: Boolean) {
    components[ModelComponent::class.java]?.isVisible = visible
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children (fades). */
internal fun Entity.setOpacity(opacity: Float) {
    val o = opacity.coerceIn(0f, 1f)
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = o else components.set(OpacityControllerComponent(o))
}

/**
 * Resources shared by several entities are made global so that destroying one user does not release
 * them from the others (Spatial Ruler finding); they are closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

internal fun unlit(
    shared: SharedResources,
    color: Color,
    blending: BlendingMode = BlendingMode.OPAQUE,
    opacity: Float = 1f,
    twoSided: Boolean = false,
    depthWrite: Boolean = true,
    texture: TextureResource? = null,
): UnlitMaterial =
    shared.keep(
        UnlitMaterial.create(blending).apply {
            setBaseColor(color.copy(alpha = 1f).color4())
            if (blending != BlendingMode.OPAQUE) setOpacity(opacity)
            runCatching { setApplyToneMapping(false) }
            if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            if (!depthWrite) setDepthWrite(false)
            if (texture != null) setBaseColorTexture(texture)
        }
    )

internal fun pbr(shared: SharedResources, color: Color, roughness: Float, metallic: Float, texture: TextureResource? = null): PhysicallyBasedMaterial =
    shared.keep(
        PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColor(color.color4())
            setRoughness(roughness)
            setMetallic(metallic)
            if (texture != null) setBaseColorTexture(texture)
        }
    )

// ---------------------------------------------------------------------------------------------
// Programmatic geometry. All in metres; nothing is loaded from a file.
// ---------------------------------------------------------------------------------------------

internal object Meshes {
    private val FRONT = Vector3(0f, 0f, 1f)
    private val UP = Vector3(0f, 1f, 0f)

    /** Rectangle in the local XY plane, centred, facing +Z, with texture coordinates. */
    fun quad(width: Float, height: Float, name: String): MeshResource {
        val w = width / 2f
        val h = height / 2f
        val positions = listOf(Vector3(-w, -h, 0f), Vector3(w, -h, 0f), Vector3(w, h, 0f), Vector3(-w, h, 0f))
        val uvs = listOf(Vector2(0f, 1f), Vector2(1f, 1f), Vector2(1f, 0f), Vector2(0f, 0f))
        val normals = List(4) { FRONT }
        val indices = listOf(0, 1, 2, 0, 2, 3)
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3.ZERO, halfExtent = Vector3(w, h, 0.001f)), name = name)
    }

    /**
     * Unit cube (-0.5..0.5), scaled per entity. `MeshResource.createBox` renders about twice the asked
     * size on the emulator (factory finding), and one shared mesh costs one creation instead of eight.
     */
    fun unitCube(name: String): MeshResource {
        val positions = ArrayList<Vector3>(24)
        val normals = ArrayList<Vector3>(24)
        val indices = ArrayList<Int>(36)
        val faces =
            listOf(
                Triple(Vector3(0f, 0f, 1f), Vector3(1f, 0f, 0f), Vector3(0f, 1f, 0f)),
                Triple(Vector3(0f, 0f, -1f), Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f)),
                Triple(Vector3(1f, 0f, 0f), Vector3(0f, 0f, -1f), Vector3(0f, 1f, 0f)),
                Triple(Vector3(-1f, 0f, 0f), Vector3(0f, 0f, 1f), Vector3(0f, 1f, 0f)),
                Triple(Vector3(0f, 1f, 0f), Vector3(1f, 0f, 0f), Vector3(0f, 0f, -1f)),
                Triple(Vector3(0f, -1f, 0f), Vector3(1f, 0f, 0f), Vector3(0f, 0f, 1f)),
            )
        for ((n, u, v) in faces) {
            val base = positions.size
            val c = Vector3(n.x * 0.5f, n.y * 0.5f, n.z * 0.5f)
            positions += Vector3(c.x - u.x * 0.5f - v.x * 0.5f, c.y - u.y * 0.5f - v.y * 0.5f, c.z - u.z * 0.5f - v.z * 0.5f)
            positions += Vector3(c.x + u.x * 0.5f - v.x * 0.5f, c.y + u.y * 0.5f - v.y * 0.5f, c.z + u.z * 0.5f - v.z * 0.5f)
            positions += Vector3(c.x + u.x * 0.5f + v.x * 0.5f, c.y + u.y * 0.5f + v.y * 0.5f, c.z + u.z * 0.5f + v.z * 0.5f)
            positions += Vector3(c.x - u.x * 0.5f + v.x * 0.5f, c.y - u.y * 0.5f + v.y * 0.5f, c.z - u.z * 0.5f + v.z * 0.5f)
            repeat(4) { normals += n }
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base
            indices += base + 2
            indices += base + 3
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3.ZERO, halfExtent = Vector3(0.5f, 0.5f, 0.5f)), name = name)
    }

    /** Solid disc in the local XZ plane, facing +Y. */
    fun disc(radius: Float, name: String, segments: Int = 48): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val normals = ArrayList<Vector3>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        normals += UP
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3((cos(a) * radius).toFloat(), 0f, (sin(a) * radius).toFloat())
            normals += UP
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + (i + 1) % segments
            indices += 1 + i
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3.ZERO, halfExtent = Vector3(radius, 0.001f, radius)), name = name)
    }

    /** Flat ring in the local XZ plane (y = 0), facing +Y. */
    fun ring(inner: Float, outer: Float, name: String, segments: Int = 72): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0 until segments) {
            val s = 2 * PI * i / segments
            val e = 2 * PI * (i + 1) / segments
            val base = positions.size
            positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
            positions += Vector3((cos(s) * outer).toFloat(), 0f, (sin(s) * outer).toFloat())
            positions += Vector3((cos(e) * outer).toFloat(), 0f, (sin(e) * outer).toFloat())
            positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
            repeat(4) { normals += UP }
            indices += base
            indices += base + 2
            indices += base + 1
            indices += base
            indices += base + 3
            indices += base + 2
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3.ZERO, halfExtent = Vector3(outer, 0.001f, outer)), name = name)
    }

    /**
     * A bent arrow in the local XY plane facing +Z, pointing to the left (-X): three quarters of a
     * ring open at the bottom and a triangular head at its end. [size] is its overall width.
     */
    fun bentArrow(size: Float, name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val r = size * 0.36f
        val t = size * 0.12f
        val steps = 18
        // arc from -60 degrees (lower right) counter-clockwise to 200 degrees (lower left), so the head points down-left
        val a0 = -60.0 * PI / 180
        val a1 = 200.0 * PI / 180
        for (i in 0 until steps) {
            val s = a0 + (a1 - a0) * i / steps
            val e = a0 + (a1 - a0) * (i + 1) / steps
            val base = positions.size
            positions += Vector3((cos(s) * (r - t / 2)).toFloat(), (sin(s) * (r - t / 2)).toFloat(), 0f)
            positions += Vector3((cos(s) * (r + t / 2)).toFloat(), (sin(s) * (r + t / 2)).toFloat(), 0f)
            positions += Vector3((cos(e) * (r + t / 2)).toFloat(), (sin(e) * (r + t / 2)).toFloat(), 0f)
            positions += Vector3((cos(e) * (r - t / 2)).toFloat(), (sin(e) * (r - t / 2)).toFloat(), 0f)
            repeat(4) { normals += FRONT }
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base
            indices += base + 2
            indices += base + 3
        }
        // head: a triangle at the end of the arc, tangent direction continues counter-clockwise
        val ex = (cos(a1) * r).toFloat()
        val ey = (sin(a1) * r).toFloat()
        val tx = (-sin(a1)).toFloat()
        val ty = cos(a1).toFloat()
        val nx = cos(a1).toFloat()
        val ny = sin(a1).toFloat()
        val head = t * 1.6f
        val base = positions.size
        positions += Vector3(ex + nx * head, ey + ny * head, 0f)
        positions += Vector3(ex - nx * head, ey - ny * head, 0f)
        positions += Vector3(ex + tx * head * 1.4f, ey + ty * head * 1.4f, 0f)
        repeat(3) { normals += FRONT }
        indices += base
        indices += base + 1
        indices += base + 2
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3.ZERO, halfExtent = Vector3(size, size, 0.001f)), name = name)
    }
}

internal fun Color.argb(): Int = toArgb()

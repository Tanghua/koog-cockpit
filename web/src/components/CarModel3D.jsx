import { Canvas, useFrame } from '@react-three/fiber'
import { useGLTF } from '@react-three/drei'
import { useEffect, useMemo, useRef, useState } from 'react'

const DEFAULT_DIST = 7.5
const MIN_DIST = DEFAULT_DIST / 1.2
const WINDOW_DROP = 0.34

function nodeByName(nodes, names) {
  return names.map(name => nodes[name]).find(Boolean)
}

function cloneBaseTransform(node) {
  return {
    position: node.position.clone(),
    rotation: node.rotation.clone(),
  }
}

function resetTransform(node, base) {
  if (!node || !base) return
  node.position.copy(base.position)
  node.rotation.copy(base.rotation)
}

function openRatio(value) {
  const numeric = Number(value) || 0
  if (numeric <= 0) return 0
  if (numeric === 1) return 1
  return Math.max(0, Math.min(1, numeric / 100))
}

function prepareLightMaterials(nodes, matcher) {
  const materials = []
  Object.values(nodes).forEach(node => {
    if (!node?.material || !matcher(node.name)) return
    const material = node.material.clone()
    node.material = material
    materials.push(material)
  })
  return materials
}

function TeslaModel({ carState }) {
  const { scene } = useGLTF(`${import.meta.env.BASE_URL}tesla-model-3.glb`)
  const flashStartedAt = useRef(-10)
  const flashCount = useRef(0)
  const model = useMemo(() => {
    const clonedScene = scene.clone(true)
    const clonedNodes = {}
    const baseTransforms = new Map()
    clonedScene.traverse(child => {
      if (!child.name) return
      clonedNodes[child.name] = child
      baseTransforms.set(child, cloneBaseTransform(child))
    })

    const lower = name => name.toLowerCase()
    const flashMaterials = prepareLightMaterials(
      clonedNodes,
      name => (
        lower(name).includes('light')
        || lower(name).includes('indicator')
        || lower(name).includes('foglight')
      ),
    )
    return {
      scene: clonedScene,
      nodes: clonedNodes,
      baseTransforms,
      flashMaterials,
      windows: {
        windowFL: nodeByName(clonedNodes, ['door_lf_glass0_0', 'door_lf_glass.0_0']),
        windowFR: nodeByName(clonedNodes, ['door_rf_glass0_0', 'door_rf_glass.0_0']),
        windowRL: nodeByName(clonedNodes, ['door_lr_glass0_0', 'door_lr_glass.0_0']),
        windowRR: nodeByName(clonedNodes, ['door_rr_glass0_0', 'door_rr_glass.0_0']),
      },
      roof: nodeByName(clonedNodes, ['glass_glass1_0', 'glass_glass.1_0']),
      frontTrunk: nodeByName(clonedNodes, ['bonnet_dummy']),
      rearTrunk: nodeByName(clonedNodes, ['boot_dummy']),
    }
  }, [scene])

  // Three.js scene nodes are mutable runtime objects. Updating their transforms
  // is the rendering API here, not mutation of React state or hook inputs.
  /* eslint-disable react-hooks/immutability */
  useEffect(() => {
    Object.entries(model.windows).forEach(([key, node]) => {
      const ratio = openRatio(carState[key])
      resetTransform(node, model.baseTransforms.get(node))
      if (!node) return
      node.position.z -= ratio * WINDOW_DROP
      node.visible = ratio < 0.96
    })

    const sunroof = carState.sunroof
    resetTransform(model.roof, model.baseTransforms.get(model.roof))
    if (model.roof) {
      model.roof.visible = sunroof !== 1
      if (sunroof === 'vent' || sunroof === 'tilt') {
        model.roof.visible = true
        model.roof.rotation.x -= sunroof === 'tilt' ? 0.18 : 0.1
        model.roof.position.z += sunroof === 'tilt' ? 0.06 : 0.03
      }
    }

    resetTransform(model.frontTrunk, model.baseTransforms.get(model.frontTrunk))
    if (model.frontTrunk && carState.frontTrunk) {
      model.frontTrunk.rotation.x -= 0.78
      model.frontTrunk.position.z += 0.03
    }

    resetTransform(model.rearTrunk, model.baseTransforms.get(model.rearTrunk))
    if (model.rearTrunk && carState.rearTrunk) {
      model.rearTrunk.rotation.x += 0.82
      model.rearTrunk.position.z += 0.04
    }
  }, [carState, model])
  /* eslint-enable react-hooks/immutability */

  useFrame(({ clock }) => {
    if (carState.flashLightsCount !== flashCount.current) {
      flashCount.current = carState.flashLightsCount
      flashStartedAt.current = clock.getElapsedTime()
    }
    const elapsed = clock.getElapsedTime() - flashStartedAt.current
    const flashActive = elapsed >= 0 && elapsed < 1.25
    const pulse = flashActive ? Math.max(0, Math.sin(elapsed * Math.PI * 8)) : 0
    const headlightIntensity = carState.headlights ? 4 : 0
    model.flashMaterials.forEach(material => {
      const flashIntensity = pulse * 6
      material.color?.setHex(flashActive || carState.headlights ? 0xffcc00 : 0xaaaaaa)
      material.emissive?.setHex(flashActive || carState.headlights ? 0xffaa00 : 0x000000)
      material.emissiveIntensity = Math.max(headlightIntensity, flashIntensity)
      material.toneMapped = !(flashActive || carState.headlights)
    })
  })

  return (
    <group scale={0.008} position={[0, -0.2, 0]} rotation={[0, 2.2619, 0]}>
      <primitive object={model.scene} />
      {(carState.chargePort || carState.charging) && (
        <group position={[-1.08, -0.82, 0.28]}>
          <mesh>
            <sphereGeometry args={[0.08, 24, 24]} />
            <meshStandardMaterial
              color={carState.charging ? '#52ff9f' : '#65b7ff'}
              emissive={carState.charging ? '#15f47a' : '#2997ff'}
              emissiveIntensity={carState.charging ? 5 : 3}
              toneMapped={false}
            />
          </mesh>
          <pointLight
            color={carState.charging ? '#52ff9f' : '#65b7ff'}
            intensity={carState.charging ? 1.8 : 1}
            distance={1.2}
          />
        </group>
      )}
    </group>
  )
}

function VehiclePreviewCapture({ onCapture }) {
  const captured = useRef(false)
  useFrame(({ gl, scene, camera }) => {
    if (captured.current) return
    let meshes = 0
    scene.traverse(node => { if (node.isMesh) meshes++ })
    if (meshes < 10) return
    captured.current = true
    gl.render(scene, camera)
    const info = { meshes, calls: gl.info.render.calls, camera: camera.position.toArray(), presentation: 'model-derived-still' }
    window.__vehicleDiagnostics = () => info
    onCapture(gl.domElement.toDataURL('image/png'))
  })
  return null
}

export default function CarModel3D({ carState }) {
  const [preview, setPreview] = useState(null)
  // A model-derived still avoids the head unit's WebGL/compositor capture mismatch.
  // Vehicle telemetry is not connected, so this is explicitly an illustrative view.
  return <div style={{width:'100%',height:'100%',position:'relative'}}>
    {preview ? <img src={preview} alt="车辆外观示意" style={{width:'100%',height:'100%',objectFit:'contain'}} /> : (
    <Canvas
      camera={{ position: [-0.14, 3.2, 6.8], fov: 38 }}
      gl={{ preserveDrawingBuffer: true }}
      frameloop="demand"
      style={{ width: '100%', height: '100%' }}
      shadows
      onCreated={({ camera }) => camera.lookAt(0, -0.2, 0)}
    >
      <ambientLight intensity={0.8} />
      <directionalLight position={[5, 8, 3]} intensity={1.5} castShadow />
      <directionalLight position={[-4, 5, -3]} intensity={0.5} />

      <TeslaModel carState={carState} />

      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, -0.2, 0]} receiveShadow>
        <planeGeometry args={[20, 20]} />
        <shadowMaterial transparent opacity={0.1} />
      </mesh>

      <hemisphereLight args={['#eaf6ff', '#748c92', 1.5]} />
      <VehiclePreviewCapture onCapture={setPreview} />
    </Canvas>
    )}
  </div>
}

useGLTF.preload(`${import.meta.env.BASE_URL}tesla-model-3.glb`)

fun main() {
    var value = 0f
    val stiffness = 200f // roughly StiffnessLow
    val damping = 1f // critical damping
    
    // Simulate 60 updates per second
    val dt = 1f / 60f
    var time = 0f
    
    var velocity = 0f
    
    for (step in 1..60) {
        // Delta from sampleCount
        value -= 1f
        
        // velocity is reset because of snapTo!
        velocity = 0f
        
        // Simulate spring physics for 1 frame (1/60th second)
        // Spring force: F = -k * x - c * v
        // Here target is 0, so x = value
        // critically damped: c = 2 * sqrt(k)
        val c = 2 * Math.sqrt(stiffness.toDouble()).toFloat()
        
        // Simple Euler integration
        val force = -stiffness * value - c * velocity
        velocity += force * dt
        value += velocity * dt
        
        time += dt
        if (step % 10 == 0) {
            println("Step $step: value = $value")
        }
    }
}

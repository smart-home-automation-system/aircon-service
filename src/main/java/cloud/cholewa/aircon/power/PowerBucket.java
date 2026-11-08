package cloud.cholewa.aircon.power;

import java.time.LocalDateTime;

//One bucket of the power of a plug (HAS-390): where it starts (house time) and the average, the lowest and the highest
//power in W of the samples in it
public record PowerBucket(LocalDateTime at, Double avg, Double min, Double max) {
}

package cloud.cholewa.aircon.api;

import cloud.cholewa.aircon.api.model.UnitReply;
import cloud.cholewa.aircon.unit.UnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

//The API of the air conditioners of the house, under the base path /home/aircon; routed by the gateway by path, every
//method (HAS-379). Takes the request and hands it to the services
@RestController
@RequiredArgsConstructor
public class AirconController {

    private final UnitService unitService;

    //every unit of the registry with a live read of its status
    @GetMapping
    Mono<List<UnitReply>> units() {
        return unitService.units();
    }
}

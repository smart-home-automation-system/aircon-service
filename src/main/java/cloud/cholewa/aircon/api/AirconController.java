package cloud.cholewa.aircon.api;

import cloud.cholewa.aircon.api.model.AirconReply;
import cloud.cholewa.aircon.unit.UnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

//The API of the air conditioners of the house, under the base path /home/aircon; routed by the gateway by path, every
//method (HAS-379). Takes the request and hands it to the services
@RestController
@RequiredArgsConstructor
public class AirconController {

    private final UnitService unitService;

    //every unit of the registry with its state as last read (HAS-381)
    @GetMapping
    Mono<List<AirconReply>> units() {
        return unitService.units();
    }

    @GetMapping("{id}")
    Mono<AirconReply> unit(@PathVariable final long id) {
        return unitService.unit(id);
    }

    //the units of a room by the name of the registry ("office", "hall up")
    @GetMapping("rooms/{room}")
    Mono<List<AirconReply>> room(@PathVariable final String room) {
        return unitService.room(room);
    }
}

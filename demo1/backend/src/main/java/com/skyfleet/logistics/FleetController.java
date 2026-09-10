package com.skyfleet.logistics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo/fleet")
public class FleetController {
    private final FleetService fleet;
    private final VisitorIdentity visitors;

    public FleetController(FleetService fleet, VisitorIdentity visitors) {
        this.fleet = fleet;
        this.visitors = visitors;
    }

    @GetMapping
    public Map<String, Object> snapshot(HttpServletRequest request, HttpServletResponse response) {
        return fleet.snapshot(visitors.resolve(request, response).visitorHash());
    }

    @PostMapping("/purchases")
    public Map<String, Object> purchase(@RequestBody PurchaseRequest body,
                                        HttpServletRequest request, HttpServletResponse response) {
        return fleet.purchase(visitors.resolve(request, response).visitorHash(), body.commandId(), body.typeId(), body.pricingMode());
    }

    @PostMapping("/sales")
    public Map<String, Object> sell(@RequestBody SaleRequest body,
                                    HttpServletRequest request, HttpServletResponse response) {
        return fleet.sell(visitors.resolve(request, response).visitorHash(), body.commandId(), body.assetId());
    }

    @PatchMapping("/assets/{assetId}/status")
    public Map<String, Object> status(@PathVariable String assetId, @RequestBody StatusRequest body,
                                      HttpServletRequest request, HttpServletResponse response) {
        return fleet.changeStatus(visitors.resolve(request, response).visitorHash(), assetId, body.commandId(), body.targetStatus());
    }

    public record PurchaseRequest(String commandId, String typeId, String pricingMode) {}
    public record SaleRequest(String commandId, String assetId) {}
    public record StatusRequest(String commandId, String targetStatus) {}
}
